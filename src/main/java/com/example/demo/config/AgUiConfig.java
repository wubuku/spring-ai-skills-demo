package com.example.demo.config;

import com.agui.server.spring.AgUiService;
import com.agui.server.streamer.AgentStreamer;
import com.agui.spring.ai.SpringAIAgent;
import com.example.demo.agent.JsonArgToolCallback;
import com.example.demo.agent.SkillCoreTools;
import com.example.demo.agent.SkillRegistry;
import com.example.demo.agent.SkillsAdvisor;
import com.example.demo.service.PromptLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * AG-UI 协议配置类
 * 负责配置 AG-UI 服务和智能体
 *
 * 工具分配原则（2026-06-12 修复崩溃）：
 * <ul>
 *   <li>后端 <b>只注册</b> {@link SkillCoreTools}（{@code loadSkill}、{@code readSkillReference}），
 *       <b>不注册</b> {@code httpRequest} / {@code buildHttpRequest}。</li>
 *   <li>所有 HTTP 调用统一由前端 CopilotKit {@code useCopilotAction} 注册的
 *       {@code httpRequest} 工具执行：浏览器从 localStorage 读取 access token，
 *       写操作弹出确认对话框等待用户点击"确认执行"，结果作为 tool_result 返回给 LLM。</li>
 *   <li>不再有"后端 httpRequest"和"前端 httpRequest"两个同名工具去重时的歧义，
 *       也不再有 {@code buildHttpRequest} 把 JSON 元数据作为文本回灌到 assistant
 *       消息导致 React 渲染崩溃的问题。</li>
 * </ul>
 *
 * 历史回退说明：之前由 {@link com.example.demo.agent.SkillTools} 同时提供后端
 * {@code httpRequest}（用于公开 API）和后端 {@code buildHttpRequest}（用于受保护 API），
 * 与前端 {@code useCopilotAction} 注册的 {@code httpRequest} 同名/近名，导致 LLM 决策混乱
 * （首选 {@code buildHttpRequest}），其返回的 JSON 元数据被 LLM 当作文本嵌入回复，
 * React 渲染时抛 "Objects are not valid as a React child" 未捕获运行时错误，页面整页白屏
 * 被 Next.js 错误覆盖层替换。
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
     * 复用现有的 ChatModel、SkillCoreTools、SkillsAdvisor 和 ChatMemory
     */
    @Bean
    public SpringAIAgent enterpriseAgent(
            @Qualifier("chatModel") ChatModel chatModel,
            SkillCoreTools skillCoreTools,
            SkillRegistry skillRegistry,
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

        // 注册 SkillCoreTools（只有 loadSkill + readSkillReference，没有 HTTP 工具）：
        //   - 所有 HTTP 调用统一由前端 useCopilotAction 注册的 httpRequest 工具执行
        //   - 避免同名工具去重歧义 / 避免 buildHttpRequest JSON 注入导致 React 崩溃
        // 关闭 Spring AI 原生工具执行（internalToolExecutionEnabled=false）：
        //   - 工具执行改由 SpringAIAgent.executeToolCallsAndReRun() 在 onComplete 中手动驱动
        //   - 这条路径对"前后端混合工具"有正确处理：
        //       * toolCallbacksForExecution 中能找到的工具（后端 loadSkill/readSkillReference）→ 正常执行
        //       * 找不到的工具（前端 httpRequest）→ 跳过（不抛异常），allToolsFound=false
        //         → 直接发送 RUN_FINISHED 而不再让 LLM 继续（不会因 stub 返回空串让 LLM 误判）
        //   - 解决了 internalToolExecutionEnabled=true 时 Spring AI 的 DefaultToolCallingManager
        //     强制把 stub 的 "" 返回注入 tool_result 导致 LLM 复读"空数据"的根因

        // 使用自定义 JsonArgToolCallback 包装器修复 Spring AI 1.1.2 的 JSON 参数反序列化问题
        List<ToolCallback> toolCallbacks = createJsonArgToolCallbacks(skillCoreTools);

        return SpringAIAgent.builder()
                .agentId("enterprise-agent")
                .chatModel(chatModel)
                .systemMessage(systemPrompt)
                .toolCallbacks(toolCallbacks)
                .toolCallbacksForExecution(toolCallbacks)
                .internalToolExecutionEnabled(false)
                .advisor(skillsAdvisor)
                .advisor(MessageChatMemoryAdvisor.builder(chatMemory).build())
                // 关键修复（2026-06-15）：必须传递 chatMemory 给 SpringAIAgent！
                // SpringAIAgent.executeToolCallsAndReRun() 和 saveToolMessagesFromInput()
                // 需要 chatMemory 来手动保存前端工具的调用和结果。
                // MessageChatMemoryAdvisor 只负责从 ChatMemory 读取历史注入 prompt，
                // 但不会自动保存前端工具的结果——这需要 SpringAIAgent 手动处理。
                .chatMemory(chatMemory)
                // maxToolCalls 从 3 提到 5：让 MiniMax-M3 推理模型在
                //   1) 偶尔 2 次 loadSkill（"商品查询" + "加入购物车" 各查一次） +
                //   2) httpRequest 实际调用（前端拦截，弹确认框）+
                //   3) 拿到 tool_result 后生成最终回答
                // 之前 maxToolCalls=3 时 LLM 用 2 轮 loadSkill 后就只剩 1 轮，
                // httpRequest 还没下发就被跳过 → 前端永远拿不到 tool → 无确认 → 无最终回答。
                .maxToolCalls(5)
                .skillRegistry(skillRegistry)
                .build();
    }

    /**
     * 创建自定义 JsonArgToolCallback 包装器列表
     * 用于修复 Spring AI 1.1.2 的 JSON 参数反序列化问题
     */
    private List<ToolCallback> createJsonArgToolCallbacks(SkillCoreTools skillCoreTools) {
        List<ToolCallback> callbacks = new ArrayList<>();

        try {
            // loadSkill 工具
            Method loadSkillMethod = SkillCoreTools.class.getMethod("loadSkill", String.class);
            ToolDefinition loadSkillDef = DefaultToolDefinition.builder()
                .name("loadSkill")
                .description("加载指定技能的完整操作指令。在使用任何技能前必须先调用此工具。")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"skillName\":{\"type\":\"string\",\"description\":\"技能名称，必须来自 available_skills 列表\"}},\"required\":[\"skillName\"]}")
                .build();
            callbacks.add(new JsonArgToolCallback(loadSkillDef, skillCoreTools, loadSkillMethod, "skillName"));

            // readSkillReference 工具
            Method readSkillRefMethod = SkillCoreTools.class.getMethod("readSkillReference", String.class, String.class);
            ToolDefinition readSkillRefDef = DefaultToolDefinition.builder()
                .name("readSkillReference")
                .description("读取技能的参考文件（适用于具有分层结构的技能，如 OpenAPI 生成的技能）")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"skillName\":{\"type\":\"string\",\"description\":\"技能名称，例如 swagger-petstore-openapi-3-0\"},\"relativePath\":{\"type\":\"string\",\"description\":\"相对于该技能 references 目录的路径，例如 resources/pet.md 或 operations/addPet.md\"}},\"required\":[\"skillName\",\"relativePath\"]}")
                .build();
            callbacks.add(new JsonArgToolCallback(readSkillRefDef, skillCoreTools, readSkillRefMethod, "skillName", "relativePath"));

        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Failed to create JsonArgToolCallbacks", e);
        }

        return callbacks;
    }
}
