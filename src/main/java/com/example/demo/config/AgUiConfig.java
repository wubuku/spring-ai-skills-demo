package com.example.demo.config;

import com.agui.server.spring.AgUiService;
import com.agui.server.streamer.AgentStreamer;
import com.agui.spring.ai.SpringAIAgent;
import com.example.demo.agent.SkillCoreTools;
import com.example.demo.agent.SkillsAdvisor;
import com.example.demo.service.PromptLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
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
 * 重要：AG-UI 模式下，HTTP 调用必须由前端持有用户 access token 来执行。
 * 因此本配置只注册 SkillCoreTools（loadSkill + readSkillReference），
 * 不注册 SkillTools 中的 httpRequest / buildHttpRequest，
 * 避免与前端 CopilotKit 的 httpRequest 工具同名/近名冲突导致 LLM tool_choice 死循环。
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
            SkillsAdvisor skillsAdvisor,
            JdbcChatMemoryRepository jdbcChatMemoryRepository,
            PromptLoader promptLoader
    ) throws Exception {

        // 复用现有的会话记忆配置
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(jdbcChatMemoryRepository)
                .maxMessages(20)
                .build();

        // 从 PromptLoader 加载企业智能体系统提示词
        String systemPrompt = promptLoader.getPrompt("prompts/enterprise-agent/system-prompt.template");

        // 创建 SpringAIAgent，复用现有的工具和顾问
        // 注意：只传 SkillCoreTools，不传 SkillTools（避免 httpRequest / buildHttpRequest 与前端 httpRequest 工具冲突）
        // maxToolCalls(5) 防御推理模型（MiniMax-M3 等）的"无限工具调用"循环
        return SpringAIAgent.builder()
                .agentId("enterprise-agent")
                .chatModel(chatModel)
                .systemMessage(systemPrompt)
                .tool(skillCoreTools)
                .advisor(skillsAdvisor)
                .advisor(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .maxToolCalls(5)
                .build();
    }
}
