package com.example.demo.config;

import com.agui.server.spring.AgUiService;
import com.agui.server.streamer.AgentStreamer;
import com.agui.spring.ai.SpringAIAgent;
import com.example.demo.agent.SkillTools;
import com.example.demo.agent.SkillsAdvisor;
import com.example.demo.service.PromptLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallback;
import java.util.Arrays;
import java.util.List;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AG-UI 协议配置类
 * 负责配置 AG-UI 服务和智能体
 *
 * 工具分配原则（2026-06-11 重构后）：
 * <ul>
 *   <li>后端注册 {@link SkillTools}（包含 {@code loadSkill}、{@code readSkillReference}、
 *       {@code httpRequest}、{@code buildHttpRequest}）。其中 {@code httpRequest} 由
 *       SpringAIAgent 拦截后直接在 Java 端执行（不会触发前端 useCopilotAction），
 *       用于调用 <b>公开 API</b>（不需要用户 access token）。</li>
 *   <li>前端通过 CopilotKit 的 {@code useCopilotAction} 也注册了一个同名 {@code httpRequest}
 *       工具，专门用于调用 <b>需要用户 access token 的受保护 API</b>，由浏览器自动注入
 *       localStorage 里的 token，且写操作会显示确认对话框等待用户点击"确认执行"。</li>
 *   <li>LLM 通过工具描述和系统提示里的决策规则，自动选择：公开接口 → 后端 httpRequest；
 *       受保护接口 → 前端 httpRequest。</li>
 * </ul>
 *
 * 历史回退说明：2026-06-11 之前的中间态曾把后端的 httpRequest 完全移除，仅依赖前端实现。
 * 这导致 LLM 在面对"浏览商品"等公开接口时也走前端通道（增加 token 传输开销 + 强制确认 UI），
 * 不符合"公开 API 后端直连"的简洁原则。现已恢复后端 httpRequest。
 */
@Configuration
public class AgUiConfig {

    /**
     * 创建 AG-UI 服务 Bean
     */
    @Bean
    public AgUiService agUiService(AgentStreamer agentStreamer, ObjectMapper objectMapper) {
        return new AgUiService(agentStreamer, objectMapper);
    }

    /**
     * 创建 Agent Streamer Bean
     */
    @Bean
    public AgentStreamer agentStreamer() {
        return new AgentStreamer();
    }

    /**
     * 创建企业智能体
     * 复用现有的 ChatModel、SkillTools、SkillsAdvisor 和 ChatMemory
     */
    @Bean
    public SpringAIAgent enterpriseAgent(
            @Qualifier("chatModel") ChatModel chatModel,
            SkillTools skillTools,
            SkillsAdvisor skillsAdvisor,
            JdbcChatMemoryRepository jdbcChatMemoryRepository,
            PromptLoader promptLoader
    ) throws Exception {

        // 复用现有的会话记忆配置
        // maxMessages 从 20 降到 4：保留"最近 2 轮对话"足够支持指代消解（"再加一个"/"上次的订单"等），
        // 但避免过多历史把"幻觉过的商品列表"等错误信息注入 prompt 污染后续 query。
        // 背景：之前 maxMessages=20，LLM 在新会话中会复读"基于之前查到的商品列表……"并编造 199/299 的假数据。
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(jdbcChatMemoryRepository)
                .maxMessages(4)
                .build();

        // 从 PromptLoader 加载企业智能体系统提示词
        String systemPrompt = promptLoader.getPrompt("prompts/enterprise-agent/system-prompt.template");

        // 注册 SkillTools 中的所有工具（loadSkill、readSkillReference、httpRequest、buildHttpRequest），
        // 启用 Spring AI 原生工具执行（internalToolExecutionEnabled=true）：由 Spring AI 内部循环
        // 自动执行 tool_calls 并在同一次 .stream() 调用中继续生成最终自然语言回答。
        // 相比 internalToolExecutionEnabled=false 走"手写 executeToolCallsAndReRun + 手动 chat memory 写入 +
        // 多次 LLM 往返"路径，原生路径是单次流式往返，最稳健，避免 Next.js dev 模式下 SSE 长连接被中断。
        // LLM 通过工具描述区分用途：
        //   - loadSkill / readSkillReference：加载技能说明 / 读取参考文件
        //   - httpRequest（后端）：直接调用公开 API（无需用户 token）
        //   - buildHttpRequest：返回 HTTP 请求元数据供前端展示确认 UI 后由前端执行（带用户 token）
        //
        // 关键：internalToolExecutionEnabled=true 时，SpringAIAgent 仅在 chatRequest.toolCallbacks(this.toolCallbacks)
        // 中读取已注册的工具，而不会读 this.toolCallbacksForExecution。
        // 之前只传 .toolCallbacksForExecution() 会出现 "No ToolCallback found for tool name: loadSkill" 错误。
        // 因此同时注册到 toolCallbacks（让 Spring AI 能找到并执行）和 toolCallbacksForExecution（保留兼容）。
        List<ToolCallback> toolCallbacks = new java.util.ArrayList<>(Arrays.asList(ToolCallbacks.from(skillTools)));

        return SpringAIAgent.builder()
                .agentId("enterprise-agent")
                .chatModel(chatModel)
                .systemMessage(systemPrompt)
                .toolCallbacks(toolCallbacks)
                .toolCallbacksForExecution(toolCallbacks)
                .internalToolExecutionEnabled(true)
                .advisor(skillsAdvisor)
                .advisor(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .maxToolCalls(1)
                .build();
    }
}
