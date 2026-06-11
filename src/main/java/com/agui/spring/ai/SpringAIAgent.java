package com.agui.spring.ai;

import com.agui.core.agent.AgentSubscriber;
import com.agui.core.agent.AgentSubscriberParams;
import com.agui.core.agent.RunAgentInput;
import com.agui.core.event.BaseEvent;
import com.agui.core.exception.AGUIException;
import com.agui.core.message.Role;
import com.agui.core.message.SystemMessage;
import com.agui.core.state.State;
import com.agui.server.LocalAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.agui.server.EventFactory.*;

/**
 * A concrete implementation of {@link LocalAgent} that integrates with Spring AI framework
 * to provide AI-powered agent capabilities.
 *
 * This agent leverages Spring AI's ChatClient to process messages and interact with
 * various chat models. It supports tools, advisors, chat memory, and streaming responses.
 * The agent handles the complete lifecycle of chat interactions including tool calls,
 * memory management, and event emission for real-time updates.
 *
 * Key features:
 * <ul>
 * <li>Integration with Spring AI ChatClient and ChatModel</li>
 * <li>Support for tool callbacks and function calling</li>
 * <li>Chat memory management for conversation persistence</li>
 * <li>Advisor pattern support for extending functionality</li>
 * <li>Streaming response handling with real-time events</li>
 * <li>Automatic tool mapping from AG-UI tools to Spring AI tools</li>
 * </ul>
 *
 * @author Pascal Wilbrink
 * @since 1.0
 */
public class SpringAIAgent extends LocalAgent {

    private static final Logger log = LoggerFactory.getLogger(SpringAIAgent.class);

    /**
     * Default maximum number of tool calls per single LLM response.
     * 防御 MiniMax-M3 等推理模型的"无限工具调用"循环：单个 response 中超过该次数的工具调用会被强制停止。
     *
     * 历史：之前没有这个限制，会导致：
     * - 8+ 次 /api/copilotkit 请求
     * - LLM 在单个 response 中发出 4-6 个相同的 httpRequest 工具调用
     * - 前端无限确认对话框 / 死循环
     *
     * Spring AI 1.1.2 的 ToolCallingChatOptions 没有 maxToolCalls 选项，
     * 因此这里在应用层强制限制。
     */
    public static final int DEFAULT_MAX_TOOL_CALLS = 5;

    /**
     * 匹配推理模型（DeepSeek-R1、MiniMax-M3、Claude w/ extended thinking 等）泄漏到正文中的
     * XML/JSX 风格标签及其内容，用于在 TEXT_MESSAGE_CONTENT 事件发出前过滤掉，
     * 避免 React 把这些标签当作未知 HTML/JSX 标签处理造成渲染错误。
     *
     * 覆盖的标签列表（按 2026-06-02 Playwright E2E 实际观察到 + 防御性追加）：
     * - <think> / <thinking>          DeepSeek-R1、Qwen-QwQ、MiniMax-M3 等推理模型
     * - <parameter> / </parameter>   工具调用参数泄漏
     * - <invoke> / </invoke>         Hermes / ToolACE 风格 tool call 格式
     * - <tool_call> / </tool_call>   v3.2 追加：Hermes 风格外层 tool_call 容器
     * - <function_calls>             部分模型对 OpenAI 工具调用的“草稿”输出
     * - <antml_thinking> / <antml_call>  Anthropic 扩展 thinking（防御性追加）
     *
     * 设计要点：
     * - 捕获组 \\1 复用同名的开闭标签，避免误删
     * - CASE_INSENSITIVE：兼容 <Parameter>、<THINK> 等大小写变体
     * - 开标签后允许属性（如 <parameter name="x">），用 [^>]* 匹配
     * - v3.2: StreamingTagFilter 额外允许 `<tool_call>` 由 `</invoke>` 等其他已知标签闭合（见 findAnyKnownClosingTag），
     *        并在 normal state 单独 strip 悬空闭标签（见 findOrphanCloseTag）
     */
    private static final Pattern THINK_TAG_PATTERN = Pattern.compile(
        "<\\s*(think(?:ing)?|parameter|invoke|tool_call|function_calls|antml_thinking|antml_call)\\b[^>]*>"
            + "[\\s\\S]*?"
            + "<\\s*/\\s*\\1\\s*>",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * 用于在 OPEN_TAG_NAME 起点处识别是否是我们关心的“已知标签”。
     * 命中后返回该标签的规范小写名（用于在 STATE 闭合时找对应闭标签）。
     */
    private static final Pattern KNOWN_OPEN_TAG_AT = Pattern.compile(
        "<\\s*(think(?:ing)?|parameter|invoke|tool_call|function_calls|antml_thinking|antml_call)\\b",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * 已知开标签的规范小写名集合。
     * 用于检测 buffer 末尾的"在途标签"——当 LLM 把开标签名字切到两个 chunk 时，
     * 单看当前 buffer 看不到完整标签（没有 '>'），但能看到一个已知标签的前缀。
     * 此时应 hold buffer 等下一 chunk，让 v3 状态机能正确拼出完整标签再决定 suppress。
     *
     * 与 KNOWN_OPEN_TAG_AT 同步更新（增删标签时两个一起改）。
     */
    private static final java.util.Set<String> KNOWN_TAG_NAMES = java.util.Set.of(
        "think", "thinking", "parameter", "invoke", "tool_call",
        "function_calls", "antml_thinking", "antml_call"
    );

    /**
     * v3.2 防御性追加：用于在 normal state 单独 strip 悬空闭标签（如 `</invoke>` 无对应开标签）。
     * 解决：Mock LLM / MiniMax-M3 等模型偶尔会输出 "<tool_call></invoke>" 这种孤儿闭标签。
     *
     * 与 KNOWN_TAG_NAMES 同步更新。
     */
    private static final Pattern ORPHAN_CLOSE_TAG_PATTERN = Pattern.compile(
        "<\\s*/\\s*(think(?:ing)?|parameter|invoke|tool_call|function_calls|antml_thinking|antml_call)\\s*>",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * 判断字符串 s 是否是某个已知开标签名的非空前缀（包括完整名字本身）。
     * 用于"在途标签"检测。
     */
    private static boolean isKnownTagPrefix(String s) {
        if (s == null || s.isEmpty()) return false;
        for (String tag : KNOWN_TAG_NAMES) {
            if (tag.startsWith(s)) return true;
        }
        return false;
    }

    /**
     * 判断 sb[openIdx] 位置的 '<' 是否是某个已知开标签的"在途起点"。
     * 即：'<' 后面（跳过空白后）的 tag-name token 是某个已知标签的前缀。
     *
     * 注意：只判断"是不是合理起点"，不判断 buffer 中是否有 '>'。
     * 是否有 '>' 由调用方决定 hold 还是 emit。
     */
    private static boolean isPartialKnownTagAt(StringBuilder sb, int openIdx) {
        int subStart = openIdx + 1;
        int len = sb.length();
        // 跳过 '<' 后的空白（容忍 "<think " / "<parameter " 等带属性前缀的情况）
        while (subStart < len && Character.isWhitespace(sb.charAt(subStart))) {
            subStart++;
        }
        // 提取 tag-name token（字母/数字/_/-，遇到非合法字符或末尾停）
        int nameStart = subStart;
        while (subStart < len) {
            char c = sb.charAt(subStart);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                subStart++;
            } else {
                break;
            }
        }
        if (nameStart == subStart) {
            return false;  // '<' 后面没有合法 tag-name 字符
        }
        return isKnownTagPrefix(sb.substring(nameStart, subStart));
    }

    /**
     * 找 buffer 中作为"在途已知开标签"的最右一个 '<' 起点。
     * <ul>
     *   <li>返回该 '&lt;' 的位置；调用方应在该位置之前输出，保留 [该位置..] 等下一 chunk。</li>
     *   <li>如果 buffer 中没有任何 '&lt;' 后面跟已知标签前缀，返回 -1。</li>
     *   <li>如果最右的 "&lt;+前缀" 之后已存在 '&gt;'，说明该标签其实已经完整了，
     *       应被 {@link #findNextOpenTag} 捕获——这种情况返回 -1（不算在途）。</li>
     * </ul>
     */
    private static int findPartialOpenTagTail(StringBuilder sb) {
        int len = sb.length();
        int lastKnownOpen = -1;
        for (int i = 0; i < len; i++) {
            if (sb.charAt(i) == '<' && isPartialKnownTagAt(sb, i)) {
                lastKnownOpen = i;
            }
        }
        if (lastKnownOpen < 0) return -1;
        // 检查 lastKnownOpen 之后是否有 '>'
        for (int i = lastKnownOpen + 1; i < len; i++) {
            if (sb.charAt(i) == '>') {
                return -1;  // 已经有 '>'，说明标签完整了，不算在途
            }
        }
        return lastKnownOpen;
    }

    /**
     * The Spring AI ChatClient used for processing chat requests and responses.
     */
    private final ChatClient chatClient;

    /**
     * Mapper utility for converting AG-UI tools to Spring AI ToolCallback instances.
     */
    private final ToolMapper toolMapper;

    /**
     * List of Spring AI advisors that modify or enhance chat behavior.
     */
    private final List<Advisor> advisors;

    /**
     * List of Spring AI tool callbacks for function calling capabilities.
     */
    private final List<ToolCallback> toolCallbacks;

    /**
     * Chat memory implementation for maintaining conversation history.
     */
    private final ChatMemory chatMemory;

    /**
     * List of Spring AI tools
     */
    private final List<Object> tools;

    /**
     * 单个 LLM response 中允许的最大工具调用次数。
     * 超过该次数时，SpringAIAgent 会强制停止该 run，避免无限循环。
     * 设为 0 或负数表示不限制。
     */
    private final int maxToolCalls;

    /**
     * Protected constructor that initializes the SpringAIAgent using the builder pattern.
     *
     * @param builder the Builder instance containing all configuration parameters
     * @throws AGUIException if the parent LocalAgent constructor validation fails
     */
    protected SpringAIAgent(
        Builder builder
    ) throws AGUIException {
        super(
            builder.agentId,
            builder.state,
            builder.systemMessageProvider,
            builder.systemMessage
        );

        this.chatClient = ChatClient.builder(builder.chatModel).build();

        this.chatMemory = builder.chatMemory;

        this.advisors = builder.advisors;

        this.toolCallbacks = builder.toolCallbacks;
        this.tools = builder.tools;
        this.maxToolCalls = builder.maxToolCalls > 0
            ? builder.maxToolCalls
            : DEFAULT_MAX_TOOL_CALLS;

        this.toolMapper = new ToolMapper();
    }

    /**
     * {@inheritDoc}
     *
     * Executes the agent by processing the latest user message through Spring AI's ChatClient.
     * The method handles the complete chat lifecycle including:
     * <ul>
     * <li>Extracting the user message from input</li>
     * <li>Setting up the chat request with tools, advisors, and memory</li>
     * <li>Streaming the response and emitting appropriate events</li>
     * <li>Handling tool calls and deferred events</li>
     * <li>Managing conversation memory</li>
     * </ul>
     *
     * Events are emitted throughout the process to provide real-time updates to subscribers.
     */
    protected void run(RunAgentInput input, AgentSubscriber subscriber) {
        var messageId = UUID.randomUUID().toString();
        var threadId = input.threadId();
        var runId = input.runId();
        var state = input.state();

        String content;

        try {
            var userMessage = this.getLatestUserMessage(input.messages());
            content = userMessage.getContent();
        } catch (AGUIException e) {
            this.emitEvent(runErrorEvent(e.getMessage()), subscriber);
            return;
        }

        this.emitEvent(
            runStartedEvent(threadId, runId),
            subscriber
        );

        this.emitEvent(
            textMessageStartEvent(messageId, "assistant"),
            subscriber
        );

        final List<BaseEvent> deferredEvents = new ArrayList<>();
        // 单次 run 中的工具调用计数器（线程安全）
        // 用于防御推理模型（MiniMax-M3 等）的"无限工具调用"循环
        final AtomicInteger toolCallCounter = new AtomicInteger(0);
        // 标记是否已因工具调用超限而强制停止
        final boolean[] forceStopped = { false };
        // 单次 run 中跨 chunk 的标签过滤器（用于剥离 <think> / <parameter> / <invoke> / <function_calls> 等
        // 推理模型在正文里泄漏的 XML/JSX 风格标签）
        final StreamingTagFilter tagFilter = new StreamingTagFilter();

        try {
            getChatRequest(input, content, messageId, deferredEvents, this.createSystemMessage(state, input.context()), subscriber)
                .stream()
                .chatResponse()
                .subscribe(
                    evt -> onEvent(subscriber, evt, messageId, deferredEvents, toolCallCounter, forceStopped, tagFilter),
                    err -> {
                        if (!forceStopped[0]) {
                            this.emitEvent(runErrorEvent(err.getMessage()), subscriber);
                        }
                    },
                    () -> {
                        if (forceStopped[0]) {
                            // 强制停止：额外发出明确的结束事件
                            this.emitEvent(textMessageEndEvent(messageId), subscriber);
                            this.emitEvent(runFinishedEvent(input.threadId(), input.runId()), subscriber);
                            log.info("[SpringAIAgent] run 已强制停止 - 工具调用超限 (limit={})", this.maxToolCalls);
                        } else {
                            onComplete(input, subscriber, messageId, deferredEvents);
                        }
                    }
                );
        } catch (AGUIException e) {
            this.emitEvent(runErrorEvent(e.getMessage()), subscriber);
        }
    }

    /**
     * Handles individual chat response events from the streaming response.
     *
     * This method processes each chunk of the streaming response and emits
     * text message content events when the response contains actual text content.
     *
     * @param subscriber the event subscriber to notify
     * @param evt the chat response event from Spring AI
     * @param messageId the unique identifier for the current message
     * @param deferredEvents Events that will be deferred and emitted later
     */
    private void onEvent(AgentSubscriber subscriber, ChatResponse evt, String messageId, List<BaseEvent> deferredEvents, AtomicInteger toolCallCounter, boolean[] forceStopped, StreamingTagFilter tagFilter) {
        // 已被强制停止，不再处理后续事件
        if (forceStopped[0]) {
            return;
        }

        if (evt.hasToolCalls()) {
            int currentCount = toolCallCounter.incrementAndGet();
            if (currentCount > this.maxToolCalls) {
                // 超过 maxToolCalls：跳过此工具调用，标记强制停止
                if (!forceStopped[0]) {
                    forceStopped[0] = true;
                    log.warn("[SpringAIAgent] 已达到最大工具调用次数 ({}),强制停止当前 run,跳过工具: {}",
                        this.maxToolCalls, evt.getResult().getOutput().getToolCalls().stream()
                            .map(tc -> tc.name() + "(" + tc.arguments() + ")")
                            .collect(Collectors.joining(", ")));

                    // 通过 text message content event 向用户发出明确的停止消息
                    String stopMsg = "\n\n⚠️ [系统提示] 已达到单次响应最大工具调用次数 ("
                        + this.maxToolCalls + ")，强制停止。请基于已收到的工具结果给出最终回答，避免继续调用工具。";
                    this.emitEvent(
                        textMessageContentEvent(messageId, stopMsg),
                        subscriber
                    );
                }
                // 仍然需要消费这个事件，但不再添加到 deferredEvents
                return;
            }
            evt.getResult().getOutput().getToolCalls()
                .forEach(toolCall -> {
                    var toolCallId = toolCall.id();
                    deferredEvents.add(toolCallStartEvent(messageId, toolCall.name(), toolCallId));
                    deferredEvents.add(toolCallArgsEvent(toolCall.arguments(), toolCallId));
                    deferredEvents.add(toolCallEndEvent(toolCallId));
                });
            log.info("[SpringAIAgent] 工具调用 #{}/{}: {} ({})",
                currentCount, this.maxToolCalls,
                evt.getResult().getOutput().getToolCalls().get(0).name(),
                evt.getResult().getOutput().getToolCalls().get(0).arguments());
        }
        if (StringUtils.hasText(evt.getResult().getOutput().getText())) {
            String text = evt.getResult().getOutput().getText();
            // 跨 chunk 过滤 <think>...</think> / <parameter>...</parameter> 等泄漏的 XML/JSX 风格标签
            // （v3 状态机版：仅依赖 per-chunk 正则会在跨 chunk 边界时漏掉 <tag>...<tag> 这种 split 情况）
            String filtered = tagFilter.process(text);
            if (StringUtils.hasText(filtered)) {
                this.emitEvent(
                    textMessageContentEvent(messageId, filtered),
                    subscriber
                );
            }
        }
    }

    /**
     * 去除推理模型（DeepSeek-R1 / MiniMax-M3 / Claude extended thinking 等）
     * 泄漏到正文中的 XML/JSX 风格标签及其内容，包括：
     *   <think> / <thinking> / <parameter> / <invoke> / <function_calls> /
     *   <antml_thinking> / <antml_call>。
     *
     * 背景：这些标签会被 React 当作未知 HTML/JSX 标签处理，触发
     * "The tag X is unrecognized in this browser" 警告并污染前端 UI。
     *
     * 注意：这里只删除成对的标签（包括其内容），不删除标签外的正文。
     * 使用 Matcher.replaceAll 而非 find + 一次替换，以保证同一文本中出现
     * 多组标签时全部被清除。
     */
    private String stripThinkTags(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return THINK_TAG_PATTERN.matcher(text).replaceAll("").trim();
    }

    /**
     * 跨 chunk 流式过滤推理模型（DeepSeek-R1 / MiniMax-M3 / Claude extended thinking 等）
     * 泄漏到正文中的 XML/JSX 风格标签（含 <parameter> / <invoke> / <function_calls> / <think> / 等）。
     *
     * <p>为什么需要 stateful per-stream 过滤器？</p>
     * 推理模型经常把 <think>...</think> 拆成多个 chunk 流式输出，例如：
     * <pre>
     *   chunk 1: "<think>\nThe user wants..."
     *   chunk 2: "...the API call.\n</think>"
     * </pre>
     * 之前基于单个 chunk 的 {@link #stripThinkTags} 因为看不到完整的开闭标签对，
     * 会原样放行 chunk 1，导致 React 收到一个独立的 <parameter> / <invoke> / <think>
     * 标签而触发 "unrecognized in this browser" 警告。
     *
     * <p>本过滤器在 run() 调用期间维护：
     * <ul>
     *   <li>{@code buffer}  - 累积跨 chunk 的未消费字符（用于识别跨 chunk 的标签）</li>
     *   <li>{@code inThinkBlock} - 当前是否处于被抑制的 think 块内</li>
     *   <li>{@code currentTagName} - 当前块对应的标签名（用于精确匹配同名的闭标签）</li>
     * </ul>
     *
     * <p>对外只暴露 {@link #process(String)}，每次把 LLM 流过来的 delta 喂进来，
     * 它会返回"可以安全发给前端"的那部分文本。</p>
     */
    private static final class StreamingTagFilter {
        private final StringBuilder buffer = new StringBuilder();
        private boolean inThinkBlock = false;
        private String currentTagName = null;

        /**
         * 把一个流式 delta 喂进过滤器，返回可以安全发给前端的文本。
         * 跨 chunk 的开标签和闭标签都会被正确识别。
         *
         * v3.2 增强：
         * - 处于被抑制块时，闭标签可以是 ANY 已知标签名（不仅限于 currentTagName），
         *   这样 `<tool_call>...<parameter>...</parameter></invoke>` 这种 Hermes 风格
         *   嵌套结构也能正确闭合。
         * - 处于 normal 状态时，单独 strip 悬空闭标签（`</invoke>` 等无对应开标签的情况），
         *   避免泄漏到前端。
         */
        public String process(String delta) {
            if (delta == null || delta.isEmpty()) {
                return "";
            }
            buffer.append(delta);

            StringBuilder out = new StringBuilder();
            while (true) {
                if (inThinkBlock) {
                    // 当前处于被抑制块：找 ANY 已知闭标签（v3.2 放宽：不再要求同名为闭合）
                    int closeIdx = findAnyKnownClosingTag(buffer, 0);
                    if (closeIdx < 0) {
                        // 还没看到闭标签，buffer 整体都属于被抑制块，直接清空
                        buffer.setLength(0);
                        return out.toString();
                    }
                    // 计算实际闭标签长度（可能包含 </tagname> 中的空白）
                    int closeTagLen = measureClosingTagLength(buffer, closeIdx);
                    buffer.delete(0, closeIdx + closeTagLen);
                    inThinkBlock = false;
                    currentTagName = null;
                    // 继续循环看 buffer 后面还有没有新的开标签
                } else {
                    // v3.2 修复：必须先找开标签，再找悬空闭标签。
                    // 原因：thin-split 等场景下，buffer 里可能同时有「上一 chunk 留下来的
                    // 在途开标签 <thin」+「本 chunk 补全的 k>」+「内部的 </think>」，
                    // 此时先看 orphan close 会把 <thin|k>...</parameter> 误判成普通文本而提前输出。
                    // 正确顺序：先认开标签 → 进入 think block → 在 think block 里再吃闭标签。
                    int openIdx = findNextOpenTag(buffer, 0);
                    if (openIdx < 0) {
                        // 没找到完整开标签。检查 buffer 中是否还有"在途标签"——
                        // LLM 把开标签名字切到了下一个 chunk（v3 之前的版本会漏掉）。
                        // 这种情况应保留 in-progress 部分等下一 chunk 再决定。
                        int partialIdx = findPartialOpenTagTail(buffer);
                        if (partialIdx >= 0) {
                            if (partialIdx > 0) {
                                out.append(buffer, 0, partialIdx);
                            }
                            buffer.delete(0, partialIdx);
                            return out.toString();
                        }
                        // v3.2 防御性追加：buffer 中既没完整开标签也没在途标签，再看悬空闭标签。
                        // 只 strip 闭标签本身，保留它之前的可见文本。
                        int orphanCloseIdx = findOrphanCloseTag(buffer, 0);
                        if (orphanCloseIdx >= 0) {
                            if (orphanCloseIdx > 0) {
                                out.append(buffer, 0, orphanCloseIdx);
                            }
                            int orphanCloseEnd = indexOfTagEnd(buffer, orphanCloseIdx);
                            if (orphanCloseEnd >= 0) {
                                buffer.delete(0, orphanCloseEnd + 1);
                                continue;
                            }
                            // 闭标签被截断（横跨到下一个 chunk），暂时保留等下一 chunk
                            if (orphanCloseIdx > 0) {
                                buffer.delete(0, orphanCloseIdx);
                            }
                            return out.toString();
                        }
                        // 没有任何开标签（既不完整也不在途）也没有悬空闭标签，正常输出整个 buffer
                        out.append(buffer);
                        buffer.setLength(0);
                        return out.toString();
                    }
                    // 输出开标签之前的部分
                    if (openIdx > 0) {
                        out.append(buffer, 0, openIdx);
                    }
                    // 看 buffer 剩余部分能不能构成完整的开标签
                    int tagEndIdx = indexOfTagEnd(buffer, openIdx);
                    if (tagEndIdx < 0) {
                        // 是不完整的开标签（横跨到下一个 chunk），把 buffer 截到开标签起点
                        // 这样下一次 process 时仍然能识别
                        buffer.delete(0, openIdx);
                        return out.toString();
                    }
                    // 完整的开标签：识别出标签名
                    String tagName = extractTagName(buffer, openIdx, tagEndIdx);
                    if (tagName == null) {
                        // 防御：理论上 KNOWN_OPEN_TAG_AT 命中后一定能解析出名字
                        out.append(buffer, openIdx, tagEndIdx + 1);
                        buffer.delete(0, tagEndIdx + 1);
                        continue;
                    }
                    // 进入被抑制状态
                    buffer.delete(0, tagEndIdx + 1);
                    inThinkBlock = true;
                    currentTagName = tagName;
                    // 继续循环看 buffer 剩余部分
                }
            }
        }

        /**
         * 从 buffer[start..] 中找下一个 KNOWN 开标签（<&thinsp;tagname&thinsp;）的起点。
         * 返回 -1 表示没有找到。
         */
        private static int findNextOpenTag(StringBuilder sb, int start) {
            int len = sb.length();
            for (int i = start; i < len; i++) {
                if (sb.charAt(i) == '<') {
                    Matcher m = KNOWN_OPEN_TAG_AT.matcher(sb.subSequence(i, len));
                    if (m.lookingAt()) {
                        return i;
                    }
                    // 不是我们的标签：把 '<' 当作普通字符吃掉继续找
                }
            }
            return -1;
        }

        /**
         * 在 buffer[start..] 中找 "</tagname>"（不区分大小写、容忍空白）。
         * 返回起点；返回 -1 表示没有。
         */
        private static int findClosingTag(StringBuilder sb, int start, String tagName) {
            String needle = "</" + tagName + ">";
            int len = sb.length();
            int needleLen = needle.length();
            String lower = sb.toString().toLowerCase();
            for (int i = start; i <= len - needleLen; i++) {
                if (lower.startsWith(needle, i)) {
                    // 容忍 </tagname > 之类的空白：检查后续字符直到 '>'
                    int j = i + 2 + tagName.length();
                    while (j < len && Character.isWhitespace(sb.charAt(j))) {
                        j++;
                    }
                    if (j < len && sb.charAt(j) == '>') {
                        return i;
                    }
                }
            }
            return -1;
        }

        /**
         * v3.2 新增：在 buffer[start..] 中找 ANY KNOWN 闭标签的起点（不要求同名为闭标签）。
         * 用于支持 Hermes 风格 `<tool_call>...<parameter>...</parameter></invoke>` 这种
         * 内外层标签名不一致的嵌套结构。
         *
         * 返回 -1 表示 buffer 中没有完整的已知闭标签。
         */
        private static int findAnyKnownClosingTag(StringBuilder sb, int start) {
            int len = sb.length();
            int bestIdx = -1;
            for (int i = start; i < len - 1; i++) {
                if (sb.charAt(i) == '<' && sb.charAt(i + 1) == '/') {
                    // 提取 tag-name token
                    int nameStart = i + 2;
                    int j = nameStart;
                    while (j < len) {
                        char c = sb.charAt(j);
                        if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                            j++;
                        } else {
                            break;
                        }
                    }
                    if (j == nameStart) {
                        continue;  // </ 后面没有合法 tag-name 字符
                    }
                    String tagName = sb.substring(nameStart, j).toLowerCase();
                    if (!KNOWN_TAG_NAMES.contains(tagName)) {
                        continue;
                    }
                    // 跳过空白到 '>'
                    int k = j;
                    while (k < len && Character.isWhitespace(sb.charAt(k))) {
                        k++;
                    }
                    if (k < len && sb.charAt(k) == '>') {
                        return i;  // 找到第一个匹配的闭标签即可（按从左到右扫描）
                    }
                }
            }
            return bestIdx;
        }

        /**
         * v3.2 新增：测量 buffer[closeIdx..] 处闭标签的实际字节长度（含空白、不含 '>' 之后的）。
         * 调用方需先确认 closeIdx 指向 '<' 且后面有 '>'。
         */
        private static int measureClosingTagLength(StringBuilder sb, int closeIdx) {
            int j = closeIdx + 2;  // 跳过 "</"
            int len = sb.length();
            while (j < len) {
                char c = sb.charAt(j);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                    j++;
                } else {
                    break;
                }
            }
            // 跳过 tag-name 后的空白
            while (j < len && Character.isWhitespace(sb.charAt(j))) {
                j++;
            }
            // 此时 sb[j] 应该是 '>'
            return j - closeIdx + 1;  // +1 包含 '>'
        }

        /**
         * v3.2 新增：在 buffer[start..] 中找第一个"悬空闭标签"（已知闭标签但当前不在被抑制块中）。
         * 用于在 normal state 单独 strip 掉 `</invoke>` / `</parameter>` / `</tool_call>` 等
         * 找不到对应开标签的孤儿闭标签。
         *
         * 注意：实现上不区分"是否真的悬空"（无对应开标签），只要看到已知闭标签就 strip。
         * 这是因为：
         * - 正常情况下，正文里不应该出现 `</knowntag>` 这种孤立闭标签
         * - 如果出现，99% 是模型输出异常（如 MiniMax-M3 的 `<tool_call></invoke>`），都应该 strip
         * - 偶尔误删一个孤立的 `</invoke>`（前面没有 `<invoke>`）的影响微乎其微，但能让前端 UI 干净
         *
         * 返回 -1 表示没有找到悬空闭标签。
         */
        private static int findOrphanCloseTag(StringBuilder sb, int start) {
            int len = sb.length();
            for (int i = start; i < len - 1; i++) {
                if (sb.charAt(i) == '<' && sb.charAt(i + 1) == '/') {
                    // 提取 tag-name token
                    int nameStart = i + 2;
                    int j = nameStart;
                    while (j < len) {
                        char c = sb.charAt(j);
                        if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                            j++;
                        } else {
                            break;
                        }
                    }
                    if (j == nameStart) {
                        continue;
                    }
                    String tagName = sb.substring(nameStart, j).toLowerCase();
                    if (!KNOWN_TAG_NAMES.contains(tagName)) {
                        continue;
                    }
                    // 跳过空白到 '>'
                    int k = j;
                    while (k < len && Character.isWhitespace(sb.charAt(k))) {
                        k++;
                    }
                    if (k < len && sb.charAt(k) == '>') {
                        return i;  // 找到第一个
                    }
                }
            }
            return -1;
        }

        /**
         * 在 buffer[openIdx..] 中找开标签结束的 '>' 位置。
         * 注意：开标签内的属性值可能包含引号但不会包含 '>'，所以简单从 openIdx 起找第一个 '>' 即可。
         * 返回 -1 表示 buffer 中没有 '>'，即开标签被截断。
         */
        private static int indexOfTagEnd(StringBuilder sb, int openIdx) {
            for (int i = openIdx + 1; i < sb.length(); i++) {
                char c = sb.charAt(i);
                if (c == '>') {
                    return i;
                }
            }
            return -1;
        }

        /**
         * 从 buffer[openIdx..tagEndIdx] 这段开标签里提取规范化小写的标签名。
         * 返回 null 表示不是已知标签。
         */
        private static String extractTagName(StringBuilder sb, int openIdx, int tagEndIdx) {
            int start = openIdx + 1;
            // 跳过开标签名前的空白
            while (start < tagEndIdx && Character.isWhitespace(sb.charAt(start))) {
                start++;
            }
            // 提取 [a-zA-Z_0-9-]+ 直到空白或 '>'
            int nameStart = start;
            while (start < tagEndIdx) {
                char c = sb.charAt(start);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                    start++;
                } else {
                    break;
                }
            }
            if (start == nameStart) {
                return null;
            }
            return sb.substring(nameStart, start).toLowerCase();
        }
    }

    /**
     * Handles the completion of the chat response stream.
     * This method is called when the streaming response is complete and handles:
     * <ul>
     * <li>Emitting the text message end event</li>
     * <li>Processing any deferred tool call events</li>
     * <li>Emitting the run finished event</li>
     * <li>Finalizing the agent run with updated state</li>
     * </ul>
     *
     * @param input the original run input parameters
     * @param subscriber the event subscriber to notify
     * @param messageId the unique identifier for the current message
     * @param deferredEvents list of tool call events to process after message completion
     */
    private void onComplete(RunAgentInput input, AgentSubscriber subscriber, String messageId, List<BaseEvent> deferredEvents) {
        this.emitEvent(textMessageEndEvent(messageId), subscriber);
        deferredEvents.forEach(deferredEvent ->
            this.emitEvent(deferredEvent, subscriber)
        );
        this.emitEvent(runFinishedEvent(input.threadId(), input.runId()), subscriber);
        subscriber.onRunFinalized(new AgentSubscriberParams(input.messages(), state, this, input));
    }

    /**
     * Constructs and configures the Spring AI ChatClient request specification.
     *
     * This method builds a complete chat request by combining:
     * <ul>
     * <li>The user message content and system message</li>
     * <li>Available tools converted to Spring AI ToolCallbacks</li>
     * <li>Configured advisors for behavior modification</li>
     * <li>Chat memory for conversation persistence</li>
     * </ul>
     *
     * @param input the run input containing messages, tools, and context
     * @param content the user message content to send
     * @param messageId unique identifier for the current message
     * @param deferredEvents list to collect events for later processing
     * @param systemMessage the formatted system message including state and context
     * @return configured ChatClient request specification ready for execution
     */
    private ChatClient.ChatClientRequestSpec getChatRequest(RunAgentInput input, String content, String messageId, List<BaseEvent> deferredEvents, SystemMessage systemMessage, AgentSubscriber subscriber) throws AGUIException {
        ChatClient.ChatClientRequestSpec chatRequest = this.chatClient.prompt(
            Prompt
                .builder()
                .content(content)
                .build()
            )
            .system(systemMessage.getContent()
        );

        if (!this.tools.isEmpty()) {
            try {
                chatRequest = chatRequest.tools(this.tools.toArray(new Object[0]));
            } catch (RuntimeException e) {
                throw new AGUIException("Could not add tools", e);
            }
        }

        if (!input.tools().isEmpty()) {
            try {
                chatRequest = chatRequest.toolCallbacks(
                    input.tools()
                        .stream()
                        .map((tool) -> this.toolMapper.toSpringTool(
                            tool,
                            messageId,
                            deferredEvents::add
                        )).toList()
                );
            } catch (RuntimeException e) {
                throw new AGUIException("Could not add Tools", e);
            }
        }

        if (!this.toolCallbacks.isEmpty()) {
            try {
                chatRequest = chatRequest.toolCallbacks(
                    this.toolCallbacks
                        .stream()
                        .map(toolCallback -> new AgUiFunctionToolCallback(toolCallback, (AgUiToolCallbackParams params) -> {
                            var toolCallId = UUID.randomUUID().toString();
                            deferredEvents.add(toolCallStartEvent(messageId, toolCallback.getToolDefinition().name(), toolCallId));
                            deferredEvents.add(toolCallArgsEvent(params.arguments(), toolCallId));
                            deferredEvents.add(toolCallEndEvent(toolCallId));
                            deferredEvents.add(toolCallResultEvent(toolCallId, params.result(), messageId, Role.tool));
                        }))
                        .collect(Collectors.toList())
                );
            } catch (RuntimeException e) {
                throw new AGUIException("Could not add Tool Callbacks", e);
            }
        }

        if (!this.advisors.isEmpty()) {
            try {
                chatRequest = chatRequest.advisors(this.advisors);
            } catch (RuntimeException e) {
                throw new AGUIException("Could not add advisors", e);
            }
        }

        if (Objects.nonNull(this.chatMemory)) {
            try {
                chatRequest.advisors(
                    PromptChatMemoryAdvisor.builder(chatMemory).build()
                );

                chatRequest.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, input.threadId()));
            } catch (RuntimeException e) {
                throw new AGUIException("Could not add chat memory", e);
            }
        }

        return chatRequest;
    }

    /**
     * Creates a new Builder instance for constructing SpringAIAgent instances.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for constructing SpringAIAgent instances using the builder pattern.
     *
     * This builder provides a fluent API for configuring all aspects of the SpringAIAgent
     * including the chat model, advisors, tools, memory, and agent-specific settings.
     * The builder validates that required components are provided before creating the agent.
     */
    public static class Builder {

        /**
         * The Spring AI ChatModel to use for processing chat requests.
         */
        private ChatModel chatModel;

        /**
         * List of Spring AI advisors to apply to chat requests.
         */
        private final List<Advisor> advisors = new ArrayList<>();

        /**
         * List of Spring AI tool callbacks for function calling.
         */
        private final List<ToolCallback> toolCallbacks = new ArrayList<>();

        /**
         * List of Spring AI tools for function calling.
         */
        private final List<Object> tools = new ArrayList<>();

        /**
         * Unique identifier for the agent being built.
         */
        private String agentId;

        /**
         * Initial state for the agent being built.
         */
        private State state;

        /**
         * Static system message content for the agent.
         */
        private String systemMessage;

        /**
         * Dynamic system message provider function.
         */
        private Function<LocalAgent, String> systemMessageProvider;

        /**
         * Chat memory implementation for conversation persistence.
         */
        private ChatMemory chatMemory;

        /**
         * 单次 LLM response 允许的最大工具调用次数。
         * 0 或负数表示使用 DEFAULT_MAX_TOOL_CALLS 默认值。
         */
        private int maxToolCalls = 0;

        /**
         * Sets the ChatModel for the agent.
         *
         * @param chatModel the Spring AI ChatModel to use
         * @return this builder instance for method chaining
         */
        public Builder chatModel(final ChatModel chatModel) {
            this.chatModel = chatModel;

            return this;
        }

        /**
         * Adds multiple advisors to the agent configuration.
         *
         * @param advisors list of Spring AI advisors to add
         * @return this builder instance for method chaining
         */
        public Builder advisors(final List<Advisor> advisors) {
            this.advisors.addAll(advisors);

            return this;
        }

        /**
         * Adds a single advisor to the agent configuration.
         *
         * @param advisor the Spring AI advisor to add
         * @return this builder instance for method chaining
         */
        public Builder advisor(final Advisor advisor) {
            this.advisors.add(advisor);

            return this;
        }

        /**
         * Adds multiple tools to the agent configuration.
         *
         * @param tools list of Spring AI tools to add
         * @return this builder instance for method chaining
         */
        public Builder tools(final List<Object> tools) {
            this.tools.addAll(tools);

            return this;
        }

        /**
         * Adds a single tool to the agent configuration
         *
         * @param tool the Spring AI tool to add
         * @return this builder instance for method chaining
         */
        public Builder tool(final Object tool) {
            this.tools.add(tool);

            return this;
        }

        /**
         * Sets the unique identifier for the agent.
         *
         * @param agentId the unique agent identifier
         * @return this builder instance for method chaining
         */
        public Builder agentId(final String agentId) {
            this.agentId = agentId;

            return this;
        }

        /**
         * Sets the initial state for the agent.
         *
         * @param state the initial agent state
         * @return this builder instance for method chaining
         */
        public Builder state(final State state) {
            this.state = state;

            return this;
        }

        /**
         * Adds multiple tool callbacks to the agent configuration.
         *
         * @param toolCallbacks list of Spring AI tool callbacks to add
         * @return this builder instance for method chaining
         */
        public Builder toolCallbacks(final List<ToolCallback> toolCallbacks) {
            this.toolCallbacks.addAll(toolCallbacks);

            return this;
        }

        /**
         * Adds a single tool callback to the agent configuration.
         *
         * @param toolCallback the Spring AI tool callback to add
         * @return this builder instance for method chaining
         */
        public Builder toolCallback(final ToolCallback toolCallback) {
            this.toolCallbacks.add(toolCallback);

            return this;
        }

        /**
         * Sets the static system message for the agent.
         *
         * @param systemMessage the static system message content
         * @return this builder instance for method chaining
         */
        public Builder systemMessage(final String systemMessage) {
            this.systemMessage = systemMessage;

            return this;
        }

        /**
         * Sets the dynamic system message provider for the agent.
         *
         * @param systemMessageProvider function that generates system messages dynamically
         * @return this builder instance for method chaining
         */
        public Builder systemMessageProvider(final Function<LocalAgent, String> systemMessageProvider) {
            this.systemMessageProvider = systemMessageProvider;

            return this;
        }

        /**
         * Sets the chat memory implementation for conversation persistence.
         *
         * @param chatMemory the Spring AI ChatMemory implementation
         * @return this builder instance for method chaining
         */
        public Builder chatMemory(final ChatMemory chatMemory) {
            this.chatMemory = chatMemory;

            return this;
        }

        /**
         * Sets the maximum number of tool calls allowed in a single LLM response.
         *
         * 当推理模型（如 MiniMax-M3、DeepSeek-R1）出现"无限工具调用"循环时，
         * SpringAIAgent 会在达到该上限后强制停止当前 run，避免死循环。
         *
         * 传入 0 或负数表示使用 {@link #DEFAULT_MAX_TOOL_CALLS} 默认值。
         *
         * @param maxToolCalls 最大工具调用次数
         * @return this builder instance for method chaining
         */
        public Builder maxToolCalls(final int maxToolCalls) {
            this.maxToolCalls = maxToolCalls;

            return this;
        }

        /**
         * Builds and returns a new SpringAIAgent instance with the configured parameters.
         *
         * @return a new SpringAIAgent instance
         * @throws AGUIException if the configuration is invalid or required parameters are missing
         */
        public SpringAIAgent build() throws AGUIException {
            return new SpringAIAgent(this);
        }
    }

}