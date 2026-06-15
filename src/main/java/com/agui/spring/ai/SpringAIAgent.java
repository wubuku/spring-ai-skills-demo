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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
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
    public static final int DEFAULT_MAX_TOOL_CALLS = 1;

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
        "<\\s*(parameter|invoke|tool_call|function_calls|antml_call)\\b[^>]*>"
            + "[\\s\\S]*?"
            + "<\\s*/\\s*\\1\\s*>",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * 用于在 OPEN_TAG_NAME 起点处识别是否是我们关心的“已知标签”。
     * 命中后返回该标签的规范小写名（用于在 STATE 闭合时找对应闭标签）。
     */
    private static final Pattern KNOWN_OPEN_TAG_AT = Pattern.compile(
        "<\\s*(parameter|invoke|tool_call|function_calls|antml_call)\\b",
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
    /**
     * 只过滤工具调用草稿标签（parameter / invoke / tool_call / function_calls / antml_call），
     * 不再过滤 think / thinking / antml_thinking——这些推理过程标签应实时推送到前端，
     * 由前端渲染为可折叠区域，让用户看到 AI 的思考进度。
     */
    private static final java.util.Set<String> KNOWN_TAG_NAMES = java.util.Set.of(
        "parameter", "invoke", "tool_call",
        "function_calls", "antml_call"
    );

    /**
     * v3.2 防御性追加：用于在 normal state 单独 strip 悬空闭标签（如 `</invoke>` 无对应开标签）。
     * 解决：Mock LLM / MiniMax-M3 等模型偶尔会输出 "<tool_call></invoke>" 这种孤儿闭标签。
     *
     * 与 KNOWN_TAG_NAMES 同步更新。
     */
    private static final Pattern ORPHAN_CLOSE_TAG_PATTERN = Pattern.compile(
        "<\\s*/\\s*(parameter|invoke|tool_call|function_calls|antml_call)\\s*>",
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
     * 用于手动执行工具调用的 ToolCallback 列表。
     * 当 internalToolExecutionEnabled=false 时，Spring AI 不会在内部执行工具，
     * 而是将原始 tool calls 透传到 subscriber。SpringAIAgent 在 onComplete() 中
     * 使用此列表查找并执行匹配的工具，发射 AG-UI 事件，然后 re-run。
     */
    private final List<ToolCallback> toolCallbacksForExecution;

    /**
     * 是否禁用 Spring AI 的内部工具执行。
     * 当为 false 时，OpenAiChatModel 会将原始 tool calls 透传到 subscriber，
     * 由 SpringAIAgent 手动执行工具并发射 AG-UI 事件。
     */
    private final boolean internalToolExecutionEnabled;

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

        this.toolCallbacksForExecution = builder.toolCallbacksForExecution;
        this.internalToolExecutionEnabled = builder.internalToolExecutionEnabled;

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
        run(input, subscriber, 0, java.util.Set.of());
    }

    /**
     * 带工具执行计数的 run 方法。
     * @param toolExecutionCount 当前 run 中已执行的工具轮次（用于防止无限循环）
     * @param alreadySavedToolCallIds 在当前 run 链中已经保存到 ChatMemory 的 toolCallId 集合，
     *        用于避免 saveToolMessagesFromInput() 重复保存。
     */
    private void run(RunAgentInput input, AgentSubscriber subscriber, int toolExecutionCount, java.util.Set<String> alreadySavedToolCallIds) {
        var messageId = UUID.randomUUID().toString();
        var threadId = input.threadId();
        var runId = input.runId();
        var state = input.state();

        // 关键修复（2026-06-15）：从 input.messages() 中提取前端工具返回的 ToolMessage，
        // 并保存到 ChatMemory。这样 LLM 在后续调用中能看到前端工具（如 httpRequest）的结果。
        //
        // 流程：
        // 1. 第一次 run：LLM 返回 loadSkill + httpRequest → 后端执行 loadSkill，httpRequest 由前端处理
        // 2. 前端执行 httpRequest，调 respond(result) → CopilotKit 发送新的 agent/run
        // 3. 新的 agent/run 的 messages 中包含 ToolMessage（httpRequest 的结果）
        // 4. 这里提取 ToolMessage 和对应的 AssistantMessage，保存到 ChatMemory
        // 5. LLM 通过 ChatMemory advisor 看到 httpRequest 已返回结果，生成最终文本回复
        //
        // alreadySavedToolCallIds: 跳过已经被 executeToolCallsAndReRun() 保存过的 toolCallId，
        // 避免重复保存导致 ChatMemory 中出现重复的 ToolResponseMessage。
        saveToolMessagesFromInput(input, alreadySavedToolCallIds);

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

        final List<BaseEvent> deferredEvents = new ArrayList<>();
        // 单次 run 中的工具调用计数器（线程安全）
        // 用于防御推理模型（MiniMax-M3 等）的"无限工具调用"循环
        final AtomicInteger toolCallCounter = new AtomicInteger(0);
        // 标记是否已因工具调用超限而强制停止
        final boolean[] forceStopped = { false };
        // 单次 run 中跨 chunk 的标签过滤器（只剥离工具调用草稿标签如 <parameter> / <invoke> / <function_calls> 等）
        // 不再过滤 <think> / <thinking> 标签——这些推理过程标签实时推送到前端做折叠显示
        final StreamingTagFilter tagFilter = new StreamingTagFilter();
        // 标记 TEXT_MESSAGE_START 是否已发射
        final boolean[] textStarted = { false };
        // 跨 chunk 的已见工具调用去重集合（key = "name:arguments"）
        // 用于防御 MiniMax-M3 等模型在单次 response 中发出多个完全相同的 tool_call
        final java.util.Set<String> seenToolKeys = new java.util.HashSet<>();
        // 保存当前 toolExecutionCount 供 onComplete 使用
        final int currentToolExecutionCount = toolExecutionCount;

        try {
            getChatRequest(input, content, messageId, deferredEvents, this.createSystemMessage(state, input.context()), subscriber)
                .stream()
                .chatResponse()
                .subscribe(
                    evt -> onEvent(subscriber, evt, messageId, deferredEvents, toolCallCounter, forceStopped, tagFilter, textStarted, seenToolKeys),
                    err -> {
                        if (!forceStopped[0]) {
                            this.emitEvent(runErrorEvent(err.getMessage()), subscriber);
                            // Critical: must call onRunFinalized() to complete the SSE stream
                            // Otherwise BFF's HttpAgent and frontend's agent.runAgent() will hang forever
                            subscriber.onRunFinalized(new AgentSubscriberParams(input.messages(), state, this, input));
                        }
                    },
                    () -> {
                        if (forceStopped[0]) {
                            // 强制停止：如果有文本已发射，发出结束事件
                            if (textStarted[0]) {
                                this.emitEvent(textMessageEndEvent(messageId), subscriber);
                            }
                            this.emitEvent(runFinishedEvent(input.threadId(), input.runId()), subscriber);
                            log.info("[SpringAIAgent] run 已强制停止 - 工具调用超限 (limit={})", this.maxToolCalls);
                        } else {
                            onComplete(input, subscriber, messageId, deferredEvents, textStarted, currentToolExecutionCount);
                        }
                    }
                );
        } catch (AGUIException e) {
            this.emitEvent(runErrorEvent(e.getMessage()), subscriber);
            // Critical: must call onRunFinalized() to complete the SSE stream
            subscriber.onRunFinalized(new AgentSubscriberParams(input.messages(), state, this, input));
        }
    }

    /**
     * 从 input.messages() 中提取前端工具返回的 ToolMessage，并保存到 ChatMemory。
     *
     * 当 CopilotKit 前端执行工具（如 httpRequest）并调用 respond(result) 后，
     * 会触发新一轮 agent/run 请求，messages 中包含：
     * - UserMessage（原始用户消息）
     * - AssistantMessage（包含 tool_calls）
     * - ToolMessage（前端工具的执行结果）
     *
     * 本方法提取 ToolMessage 和对应的 AssistantMessage，转换为 Spring AI 格式保存到 ChatMemory，
     * 这样 LLM 通过 ChatMemory advisor 就能看到前端工具的执行结果。
     */
    private void saveToolMessagesFromInput(RunAgentInput input, java.util.Set<String> alreadySavedToolCallIds) {
        log.info("[SpringAIAgent] saveToolMessagesFromInput: chatMemory={}, messages={}, alreadySaved={}",
            this.chatMemory != null ? "present" : "null",
            input.messages() != null ? input.messages().size() : "null",
            alreadySavedToolCallIds.size());

        if (this.chatMemory == null || input.messages() == null || input.messages().isEmpty()) {
            return;
        }

        var messages = input.messages();

        // 调试日志：打印所有消息类型
        log.info("[SpringAIAgent] saveToolMessagesFromInput: 共 {} 条消息", messages.size());
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            String contentPreview = msg.getContent() != null ?
                msg.getContent().substring(0, Math.min(100, msg.getContent().length())) : "null";
            log.info("[SpringAIAgent]   [{}] type={}, content={}", i, msg.getClass().getSimpleName(), contentPreview);
        }

        // 关键修复（2026-06-15）：只保存最后一次 AssistantMessage(toolCalls) 之后的 ToolMessage。
        // CopilotKit 每次请求都会发送完整的对话历史（input.messages()），如果遍历所有消息，
        // 会把之前已经保存过的 ToolMessage 再次保存，导致 ChatMemory 中出现重复的 ToolResponseMessage。
        //
        // 策略：找到 input.messages() 中最后一个包含 toolCalls 的 AssistantMessage 的位置，
        // 只保存该位置之后的 ToolMessage。这些是"最新的"工具结果，尚未保存到 ChatMemory。
        int lastAssistantWithToolCallsIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            var msg = messages.get(i);
            if (msg instanceof com.agui.core.message.AssistantMessage am
                    && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                lastAssistantWithToolCallsIdx = i;
                break;
            }
        }

        if (lastAssistantWithToolCallsIdx < 0) {
            log.info("[SpringAIAgent] saveToolMessagesFromInput: 没有找到包含 toolCalls 的 AssistantMessage，跳过");
            return;
        }

        log.info("[SpringAIAgent] saveToolMessagesFromInput: 最后一个包含 toolCalls 的 AssistantMessage 在位置 {}，只处理之后的 ToolMessage",
            lastAssistantWithToolCallsIdx);

        List<Message> messagesToSave = new ArrayList<>();

        // 只遍历 lastAssistantWithToolCallsIdx 之后的消息，找到 ToolMessage 并保存
        for (int i = lastAssistantWithToolCallsIdx + 1; i < messages.size(); i++) {
            var msg = messages.get(i);

            if (msg instanceof com.agui.core.message.ToolMessage toolMsg) {
                // 跳过已经被 executeToolCallsAndReRun() 保存过的 toolCallId，避免重复保存
                if (alreadySavedToolCallIds.contains(toolMsg.getToolCallId())) {
                    log.info("[SpringAIAgent] 跳过已保存的工具结果: toolCallId={}", toolMsg.getToolCallId());
                    continue;
                }

                // 从 lastAssistantWithToolCallsIdx 处的 AssistantMessage 查找工具名
                com.agui.core.message.AssistantMessage correspondingAssistant =
                    (com.agui.core.message.AssistantMessage) messages.get(lastAssistantWithToolCallsIdx);

                // 查找工具名（从 tool_call 中）
                String toolName = "unknown";
                if (correspondingAssistant.getToolCalls() != null) {
                    toolName = correspondingAssistant.getToolCalls().stream()
                        .filter(tc -> toolMsg.getToolCallId() != null
                            && toolMsg.getToolCallId().equals(tc.id()))
                        .map(tc -> tc.function().name())
                        .findFirst()
                        .orElse("unknown");
                }

                // 重要（2026-06-15）：不能用 ToolResponseMessage 保存前端工具结果！
                // Spring AI 1.1.2 的 JdbcChatMemoryRepository.MessageRowMapper 在反序列化时
                // 会用 responses(List.of()) 创建空的 responses 列表，导致工具结果丢失。
                // LLM 看到空的 tool result 会重复调用 httpRequest。
                //
                // 解决方案：将工具结果保存为 AssistantMessage，让 LLM 能看到结果内容。
                String toolResultContent = toolMsg.getContent() != null ? toolMsg.getContent() : "";
                String toolResultText = "[工具调用结果] " + toolName + " (toolCallId: " + toolMsg.getToolCallId() + "):\n" + toolResultContent;
                org.springframework.ai.chat.messages.AssistantMessage toolResultAsAssistant =
                    new org.springframework.ai.chat.messages.AssistantMessage(toolResultText);

                messagesToSave.add(toolResultAsAssistant);
                log.info("[SpringAIAgent] 从前端消息中提取工具结果并保存为 AssistantMessage: toolCallId={}, toolName={}, contentLen={}",
                    toolMsg.getToolCallId(), toolName, toolResultContent.length());
            }
        }

        if (!messagesToSave.isEmpty()) {
            this.chatMemory.add(input.threadId(), messagesToSave);
            log.info("[SpringAIAgent] 已保存 {} 条前端工具结果到 ChatMemory (conversationId={})",
                messagesToSave.size(), input.threadId());
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
     * @param toolCallCounter Counter for tool calls in this run
     * @param forceStopped Whether the run has been force-stopped
     * @param tagFilter Streaming tag filter for think/parameter/invoke tags
     * @param textBuffer Buffer for text content (emitted only if no tool calls)
     * @param textStarted Whether TEXT_MESSAGE_START has been emitted
     */
    private void onEvent(AgentSubscriber subscriber, ChatResponse evt, String messageId, List<BaseEvent> deferredEvents, AtomicInteger toolCallCounter, boolean[] forceStopped, StreamingTagFilter tagFilter, boolean[] textStarted, java.util.Set<String> seenToolKeys) {
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
                    // 注意：这里需要直接发射（不经过缓冲），因为是强制停止的提示
                    if (!textStarted[0]) {
                        this.emitEvent(textMessageStartEvent(messageId, "assistant"), subscriber);
                        textStarted[0] = true;
                    }
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
                    // 去重：同名同参数的工具调用只保留第一个
                    // 解决 MiniMax-M3 等模型在单次 response 中发出多个完全相同 tool_call 的问题
                    String toolKey = toolCall.name() + ":" + toolCall.arguments();
                    if (seenToolKeys.contains(toolKey)) {
                        log.warn("[SpringAIAgent] 跳过重复工具调用: {} ({})", toolCall.name(), toolCall.arguments());
                        return;
                    }
                    seenToolKeys.add(toolKey);

                    var toolCallId = toolCall.id();
                    // 关键修复（2026-06-15）：立即发射工具调用事件，不延迟到 onComplete()。
                    // 这样前端能在 LLM 还在生成时就开始执行 httpRequest，
                    // 而不是等到 RUN_FINISHED 后才收到工具调用（那时已经太晚了）。
                    this.emitEvent(toolCallStartEvent(messageId, toolCall.name(), toolCallId), subscriber);
                    this.emitEvent(toolCallArgsEvent(toolCall.arguments(), toolCallId), subscriber);
                    this.emitEvent(toolCallEndEvent(toolCallId), subscriber);
                    // 记录到 deferredEvents，让 onComplete() 知道有工具调用并提取参数
                    deferredEvents.add(toolCallStartEvent(messageId, toolCall.name(), toolCallId));
                    deferredEvents.add(toolCallArgsEvent(toolCall.arguments(), toolCallId));
                });
            log.info("[SpringAIAgent] 工具调用 #{}/{}: {} ({})",
                currentCount, this.maxToolCalls,
                evt.getResult().getOutput().getToolCalls().get(0).name(),
                evt.getResult().getOutput().getToolCalls().get(0).arguments());
        }
        if (StringUtils.hasText(evt.getResult().getOutput().getText())) {
            String text = evt.getResult().getOutput().getText();
            log.debug("[SpringAIAgent] LLM raw text chunk ({} chars): {}", text.length(),
                text.length() > 200 ? text.substring(0, 200) + "..." : text);
            // 跨 chunk 过滤 <think>...</think> / <parameter>...</parameter> 等泄漏的 XML/JSX 风格标签
            // （v3 状态机版：仅依赖 per-chunk 正则会在跨 chunk 边界时漏掉 <tag>...<tag> 这种 split 情况）
            String filtered = tagFilter.process(text);
            if (StringUtils.hasText(filtered)) {
                // 实时发射文本（真流式）——不做缓冲
                if (!textStarted[0]) {
                    this.emitEvent(textMessageStartEvent(messageId, "assistant"), subscriber);
                    textStarted[0] = true;
                }
                this.emitEvent(textMessageContentEvent(messageId, filtered), subscriber);
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
     * <li>Executing tools manually when internalToolExecutionEnabled=false</li>
     * <li>Emitting the run finished event</li>
     * <li>Finalizing the agent run with updated state</li>
     * </ul>
     *
     * @param input the original run input parameters
     * @param subscriber the event subscriber to notify
     * @param messageId the unique identifier for the current message
     * @param deferredEvents list of tool call events to process after message completion
     * @param textStarted whether TEXT_MESSAGE_START has been emitted
     * @param toolExecutionCount current tool execution round count (for loop prevention)
     */
    private void onComplete(RunAgentInput input, AgentSubscriber subscriber, String messageId, List<BaseEvent> deferredEvents, boolean[] textStarted, int toolExecutionCount) {
        boolean hasToolCalls = !deferredEvents.isEmpty();

        log.info("[SpringAIAgent] onComplete: hasToolCalls={}, textStarted={}, internalToolExecutionEnabled={}, toolExecutionCount={}",
            hasToolCalls, textStarted[0], this.internalToolExecutionEnabled, toolExecutionCount);

        // 兜底：stream 完成但既没有文本也没有工具调用（例如 LLM 只输出了被全部过滤的内容）
        if (!hasToolCalls && !textStarted[0]) {
            log.warn("[SpringAIAgent] Run completed with no text and no tool calls");
            this.emitEvent(textMessageStartEvent(messageId, "assistant"), subscriber);
            textStarted[0] = true;
            this.emitEvent(textMessageContentEvent(messageId, "抱歉，模型未能生成有效回复，请重试。"), subscriber);
        }

        // 发射 TEXT_MESSAGE_END（如果 TEXT_MESSAGE_START 已发射）
        if (textStarted[0]) {
            this.emitEvent(textMessageEndEvent(messageId), subscriber);
        }

        // 当 internalToolExecutionEnabled=false 时，手动执行工具并 re-run
        if (hasToolCalls && !this.internalToolExecutionEnabled
                && !this.toolCallbacksForExecution.isEmpty()
                && toolExecutionCount < this.maxToolCalls) {
            try {
                boolean executed = executeToolCallsAndReRun(input, subscriber, messageId, deferredEvents, toolExecutionCount);
                if (executed) {
                    // 工具已执行并 re-run，不发射 RUN_FINISHED（由 re-run 负责）
                    return;
                }
            } catch (Exception e) {
                log.error("[SpringAIAgent] 工具执行失败: {}", e.getMessage(), e);
                // 发射错误事件，然后继续发射 RUN_FINISHED
                this.emitEvent(runErrorEvent("工具执行失败: " + e.getMessage()), subscriber);
            }
        } else if (hasToolCalls && !this.internalToolExecutionEnabled && toolExecutionCount >= this.maxToolCalls) {
            log.warn("[SpringAIAgent] 工具执行轮次超限 ({}), 跳过工具执行", toolExecutionCount);
        }

        // 注意：工具调用事件已在 onEvent() 中实时发射，不再需要在这里延迟发射。
        // deferredEvents 现在仅用于标记"是否有工具调用"（hasToolCalls 检查）。
        this.emitEvent(runFinishedEvent(input.threadId(), input.runId()), subscriber);
        subscriber.onRunFinalized(new AgentSubscriberParams(input.messages(), state, this, input));
    }

    /**
     * 手动执行工具调用并 re-run。
     * 当 internalToolExecutionEnabled=false 时，Spring AI 不会自动执行工具，
     * 而是将原始 tool calls 透传到 subscriber。本方法：
     * 1. 从 deferredEvents 中提取工具调用信息
     * 2. 通过 toolCallbacksForExecution 查找并执行匹配的工具
     * 3. 发射 AG-UI 工具事件（TOOL_CALL_START/ARGS/END/RESULT）
     * 4. 构建包含工具结果的新消息列表
     * 5. 重新调用 run() 让模型基于工具结果生成回复
     *
     * @return true 如果成功执行工具并 re-run，false 如果没有可执行的工具
     */
    private boolean executeToolCallsAndReRun(RunAgentInput input, AgentSubscriber subscriber, String messageId, List<BaseEvent> deferredEvents, int toolExecutionCount) {
        // 从 deferredEvents 中提取工具调用信息
        // 先收集 TOOL_CALL_START 事件的工具名和 ID
        Map<String, String> toolCallNames = new HashMap<>();  // toolCallId -> toolName
        for (BaseEvent event : deferredEvents) {
            if (event instanceof com.agui.core.event.ToolCallStartEvent startEvent) {
                toolCallNames.put(startEvent.getToolCallId(), startEvent.getToolCallName());
            }
        }

        // 收集 TOOL_CALL_ARGS 事件的参数
        Map<String, String> toolCallArgs = new HashMap<>();  // toolCallId -> arguments
        log.info("[SpringAIAgent] executeToolCallsAndReRun: deferredEvents 数量={}, 类型列表={}",
            deferredEvents.size(),
            deferredEvents.stream().map(e -> e.getClass().getSimpleName()).distinct().collect(Collectors.joining(", ")));
        for (BaseEvent event : deferredEvents) {
            if (event instanceof com.agui.core.event.ToolCallArgsEvent argsEvent) {
                toolCallArgs.put(argsEvent.getToolCallId(), argsEvent.getDelta());
                log.info("[SpringAIAgent] 收集 TOOL_CALL_ARGS: toolCallId={}, delta={}", argsEvent.getToolCallId(), argsEvent.getDelta());
            }
        }
        log.info("[SpringAIAgent] executeToolCallsAndReRun: 收集到 {} 个工具参数", toolCallArgs.size());

        // 构建 ToolCall 列表
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (Map.Entry<String, String> entry : toolCallNames.entrySet()) {
            String toolCallId = entry.getKey();
            String toolName = entry.getValue();
            String arguments = toolCallArgs.getOrDefault(toolCallId, "{}");
            toolCalls.add(new AssistantMessage.ToolCall(toolCallId, "function", toolName, arguments));
        }

        if (toolCalls.isEmpty()) {
            log.warn("[SpringAIAgent] executeToolCallsAndReRun: 没有找到工具调用");
            return false;
        }

        log.info("[SpringAIAgent] executeToolCallsAndReRun: 执行 {} 个工具调用 (轮次 {}/{})",
            toolCalls.size(), toolExecutionCount + 1, this.maxToolCalls);

        // 注意：工具调用事件（TOOL_CALL_START/ARGS/END）已在 onEvent() 中实时发射，
        // 不需要在这里重新发射。deferredEvents 仅用于提取工具调用信息。
        // 如果在这里重新发射 TOOL_CALL_START，会导致 @ag-ui/client 的 verifyEvents
        // 看到未关闭的工具调用，报错 "Cannot send 'RUN_FINISHED' while tool calls are still active"。

        // 执行工具并收集结果
        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
        boolean allToolsFound = true;  // 跟踪是否所有工具都在本地找到
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.name();
            String toolArguments = toolCall.arguments();

            // 在 toolCallbacksForExecution 中查找匹配的工具
            ToolCallback toolCallback = this.toolCallbacksForExecution.stream()
                .filter(cb -> toolName.equals(cb.getToolDefinition().name()))
                .findFirst()
                .orElse(null);

            String result;
            if (toolCallback != null) {
                try {
                    log.info("[SpringAIAgent] 执行工具: {} ({})", toolName, toolArguments);
                    result = toolCallback.call(toolArguments != null ? toolArguments : "{}");
                    log.info("[SpringAIAgent] 工具 {} 返回结果 ({} chars)", toolName, result != null ? result.length() : 0);
                } catch (Exception e) {
                    log.error("[SpringAIAgent] 工具 {} 执行失败: {}", toolName, e.getMessage());
                    result = "工具执行失败: " + e.getMessage();
                }
            } else {
                // 工具不在本地注册（如 httpRequest 由前端 useCopilotAction 处理）。
                // 标记为未找到，不发射 TOOL_CALL_RESULT——让前端 CopilotKit 的
                // useCopilotAction 处理该工具调用并返回结果。
                log.info("[SpringAIAgent] 工具 {} 未在后端注册，跳过执行（由前端处理）", toolName);
                allToolsFound = false;
                continue;
            }

            // 发射 TOOL_CALL_RESULT 事件（仅限本地已注册的工具）
            this.emitEvent(toolCallResultEvent(toolCall.id(), result, messageId, Role.tool), subscriber);

            toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, result != null ? result : ""));
        }

        // 将工具调用和结果保存到 ChatMemory，这样 re-run 时 ChatMemory advisor 会将它们注入上下文。
        // 注意：不修改 ag-ui messages（List<BaseMessage>），因为 ag-ui 和 Spring AI 的消息类型不兼容。
        // 关键修复（2026-06-15）：必须在 !allToolsFound 检查之前保存！
        // 当存在前端工具（如 httpRequest）时，后端执行的工具（如 loadSkill）的结果需要
        // 立即保存到 ChatMemory，否则下一轮 run 时 LLM 看不到已执行工具的结果。
        //
        // savedToolCallIds: 收集本轮保存到 ChatMemory 的 toolCallId，传递给下一轮 run()，
        // 让 saveToolMessagesFromInput() 跳过这些已保存的 ID，避免重复保存。
        java.util.Set<String> savedToolCallIds = new java.util.HashSet<>();
        if (this.chatMemory != null) {
            // 助手的工具调用消息（包含所有工具调用，包括前端工具）
            // 注意：content 不能为空字符串，否则 PostgreSQL 的 not-null 约束会报错
            AssistantMessage assistantMessage = AssistantMessage.builder()
                .toolCalls(toolCalls)
                .content("")  // 设置空字符串而非 null
                .build();

            if (!toolResponses.isEmpty()) {
                // 有后端工具执行结果：保存 assistant + tool results (as AssistantMessage)
                // 重要（2026-06-15）：不能用 ToolResponseMessage！JdbcChatMemoryRepository 反序列化时
                // 会丢失 responses 数据（bug in Spring AI 1.1.2 MessageRowMapper）。
                // 改用 AssistantMessage 保存工具结果，确保 LLM 能看到结果内容。
                StringBuilder toolResultsText = new StringBuilder();
                for (ToolResponseMessage.ToolResponse tr : toolResponses) {
                    toolResultsText.append("[工具调用结果] ").append(tr.name())
                        .append(" (toolCallId: ").append(tr.id()).append("):\n")
                        .append(tr.responseData()).append("\n\n");
                }
                AssistantMessage toolResultsAsAssistant = new AssistantMessage(toolResultsText.toString().trim());
                this.chatMemory.add(input.threadId(), List.of(assistantMessage, toolResultsAsAssistant));
                // 收集已保存的 toolCallId（后端执行的工具结果）
                toolResponses.forEach(tr -> savedToolCallIds.add(tr.id()));
                // 也收集前端工具的 toolCallId（assistant message 中的 tool_calls 包含所有工具）
                // 这些前端工具虽然没有执行结果，但 assistant message 已保存，不需要在
                // saveToolMessagesFromInput() 中重复保存对应的 ToolResponseMessage
                toolCalls.forEach(tc -> {
                    if (!toolResponses.stream().anyMatch(tr -> tr.id().equals(tc.id()))) {
                        savedToolCallIds.add(tc.id());
                    }
                });
                log.info("[SpringAIAgent] 已保存工具调用和结果到 ChatMemory (conversationId={}, toolResponses={}, savedToolCallIds={})", input.threadId(), toolResponses.size(), savedToolCallIds);
            } else if (!allToolsFound) {
                // 所有工具都是前端工具（没有后端执行结果）：只保存 assistant message
                // 前端工具的结果会在下一轮 run 的 saveToolMessagesFromInput() 中保存
                this.chatMemory.add(input.threadId(), List.of(assistantMessage));
                // 收集所有前端工具的 toolCallId，让 saveToolMessagesFromInput() 知道
                // 这些工具的 assistant message 已保存，只需要保存 ToolResponseMessage
                // 注意：这里不添加到 savedToolCallIds，因为前端工具的 ToolResponseMessage
                // 还没有保存（需要等前端执行结果）
                log.info("[SpringAIAgent] 已保存前端工具调用到 ChatMemory (conversationId={}, 等待前端执行结果)", input.threadId());
            }
        }

        // 关键修复（2026-06-12 解决前端循环）：
        // 如果有工具未在后端注册（!allToolsFound，如 httpRequest 由前端 useCopilotAction 接管），
        // 本轮没产生任何可注入 ChatMemory 的本地 tool_result。
        // 此时 LLM 没有看到工具返回结果——再 re-run 一次会让 LLM 在没有任何反馈的情况下
        // 再次生成 httpRequest 调用，循环往复（httpRequest → skip → re-run → httpRequest → ...）
        // 直至 maxToolCalls 用完，前端一直收不到 RUN_FINISHED 后的真正回复。
        //
        // 正确做法：发射 RUN_FINISHED 结束当前 run，不再递归调用 run()。
        // CopilotKit 的事件序列要求：
        //   TOOL_CALL_START → TOOL_CALL_ARGS → TOOL_CALL_END → RUN_FINISHED
        // @ag-ui/client 的 verifyEvents 会校验：RUN_FINISHED 之前必须关闭所有活跃的工具调用。
        // 工具调用事件（TOOL_CALL_START/ARGS/END）已在 onEvent() 中正确发射，
        // 所以这里发射 RUN_FINISHED 是安全的。
        //
        // 前端的 useCopilotAction 拿到 tool_call 后会执行 fetch → 调 respond() 提交 tool_result →
        // CopilotKit 客户端会触发新一轮 /api/copilotkit 请求，backend 新的 run() 在新请求中处理
        // 那条带 tool_result 的消息。这是"前端驱动下一轮"的标准模式。
        if (!allToolsFound) {
            log.info("[SpringAIAgent] 检测到未本地执行的工具（如 httpRequest），发射 RUN_FINISHED 并等待前端 respond() 触发下一轮");
            this.emitEvent(runFinishedEvent(input.threadId(), input.runId()), subscriber);
            subscriber.onRunFinalized(new AgentSubscriberParams(input.messages(), input.state(), this, input));
            return true;
        }

        // re-run（递归调用，增加工具执行计数）
        // 使用原始 ag-ui messages（不包含 Spring AI 消息），ChatMemory advisor 会注入完整历史
        log.info("[SpringAIAgent] 工具执行完成，re-run (轮次 {})", toolExecutionCount + 1);
        // 先发射 RUN_FINISHED 结束当前 run，再启动新的 run（发射 RUN_STARTED）
        // 否则 EventVerifier 会报 "Cannot send multiple RUN_STARTED" 错误
        this.emitEvent(runFinishedEvent(input.threadId(), input.runId()), subscriber);
        // 传递 savedToolCallIds 给下一轮 run，让 saveToolMessagesFromInput() 跳过已保存的工具结果
        run(input, subscriber, toolExecutionCount + 1, savedToolCallIds);
        return true;
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

        // 当 internalToolExecutionEnabled=false 时，工具不在 Spring AI 内部执行。
        // 但 LLM 仍然需要看到工具 schema 才能决定要不要调用 httpRequest。
        // 通过 ToolCallingChatOptions 注册"合并后的工具列表"（后端 + 前端 stub），
        // 这样 Spring AI 把所有工具 schema 写入请求体但不会自己执行 tool_calls。
        // 原始 tool_calls 通过 subscriber.onEvent() 透传，再由
        // executeToolCallsAndReRun() 手动驱动执行。
        //
        // 注意：这里必须直接用 options() 注册，不能再用 chatRequest.toolCallbacks()，
        // 否则会覆盖上面的设置。ToolCallingChatOptions.builder() 构造的工具 list
        // 内部使用 stub callback（call() 返回空），但 schema 完整。
        if (!this.internalToolExecutionEnabled) {
            try {
                // 收集所有需要让 LLM 看到的工具：
                //   - 后端工具：this.toolCallbacks（loadSkill / readSkillReference）
                //   - 前端工具：input.tools()（httpRequest 等 CopilotKit 注入的）
                // 这里用临时 ToolCallback 包装，仅用于 schema 注册（call() 不会被调用，
                // 因为 internalToolExecutionEnabled=false 关闭了 Spring AI 的内部执行）。
                List<ToolCallback> schemaOnlyTools = new java.util.ArrayList<>();
                for (ToolCallback backend : this.toolCallbacks) {
                    schemaOnlyTools.add(backend);
                }
                if (!input.tools().isEmpty()) {
                    for (var tool : input.tools()) {
                        String toolName = tool.name();
                        boolean duplicate = this.toolCallbacks.stream()
                            .anyMatch(cb -> toolName.equals(cb.getToolDefinition().name()));
                        if (duplicate) {
                            // 与后端同名：保留后端版本即可（已在 schemaOnlyTools 里）
                            continue;
                        }
                        // 前端工具：用 toolMapper 生成的 stub 携带 ToolDefinition
                        // stub 的 call() 返回 ""，但因为 internalToolExecutionEnabled=false 不会被调用
                        schemaOnlyTools.add(this.toolMapper.toSpringTool(tool, messageId, event -> {}));
                    }
                }
                if (!schemaOnlyTools.isEmpty()) {
                    ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                        .toolCallbacks(schemaOnlyTools)
                        .internalToolExecutionEnabled(false)
                        .build();
                    chatRequest = chatRequest.options(toolOptions);
                    log.info("[SpringAIAgent] 注册 {} 个工具 schema (internalToolExecutionEnabled=false): {}",
                        schemaOnlyTools.size(),
                        schemaOnlyTools.stream()
                            .map(cb -> cb.getToolDefinition().name())
                            .collect(Collectors.joining(", ")));
                }
            } catch (RuntimeException e) {
                throw new AGUIException("Could not add ToolCallingChatOptions", e);
            }
        }

        if (!this.tools.isEmpty()) {
            try {
                log.info("[SpringAIAgent] Registering {} tools via chatRequest.tools(): {}",
                    this.tools.size(),
                    this.tools.stream().map(t -> t.getClass().getSimpleName()).collect(Collectors.joining(", ")));
                chatRequest = chatRequest.tools(this.tools.toArray(new Object[0]));
            } catch (RuntimeException e) {
                throw new AGUIException("Could not add tools", e);
            }
        }

        // 工具注册已在上面（"internalToolExecutionEnabled=false" 分支）通过
        // ToolCallingChatOptions 统一完成，这里只做诊断日志（去重跳过统计）。
        // 不再调用 chatRequest.toolCallbacks(...) —— 它会覆盖上面的 options 设置。
        if (!input.tools().isEmpty()) {
            java.util.Set<String> backendToolNames = this.toolCallbacks.stream()
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toSet());
            java.util.List<String> skippedDuplicateNames = new java.util.ArrayList<>();
            for (var tool : input.tools()) {
                if (backendToolNames.contains(tool.name())) {
                    skippedDuplicateNames.add(tool.name());
                }
            }
            if (!skippedDuplicateNames.isEmpty()) {
                log.info("[SpringAIAgent] 跳过 {} 个与后端重复的前端工具: {}",
                    skippedDuplicateNames.size(), String.join(", ", skippedDuplicateNames));
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
         * 用于手动执行工具调用的 ToolCallback 列表。
         * 当 internalToolExecutionEnabled=false 时，SpringAIAgent 使用此列表查找并执行工具。
         */
        private final List<ToolCallback> toolCallbacksForExecution = new ArrayList<>();

        /**
         * 是否禁用 Spring AI 的内部工具执行。
         * 默认为 true（Spring AI 内部执行工具）。
         */
        private boolean internalToolExecutionEnabled = true;

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
         * 添加用于手动执行工具调用的 ToolCallback。
         * 当 internalToolExecutionEnabled=false 时，SpringAIAgent 使用此列表查找并执行工具。
         *
         * @param toolCallback the ToolCallback to add
         * @return this builder instance for method chaining
         */
        public Builder toolCallbackForExecution(final ToolCallback toolCallback) {
            this.toolCallbacksForExecution.add(toolCallback);
            return this;
        }

        /**
         * 设置用于手动执行工具调用的 ToolCallback 列表。
         *
         * @param toolCallbacks the list of ToolCallbacks
         * @return this builder instance for method chaining
         */
        public Builder toolCallbacksForExecution(final List<ToolCallback> toolCallbacks) {
            this.toolCallbacksForExecution.addAll(toolCallbacks);
            return this;
        }

        /**
         * 设置是否禁用 Spring AI 的内部工具执行。
         * 当为 false 时，OpenAiChatModel 会将原始 tool calls 透传到 subscriber，
         * 由 SpringAIAgent 手动执行工具并发射 AG-UI 事件。
         *
         * @param enabled true 表示 Spring AI 内部执行（默认），false 表示透传到 subscriber
         * @return this builder instance for method chaining
         */
        public Builder internalToolExecutionEnabled(final boolean enabled) {
            this.internalToolExecutionEnabled = enabled;
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