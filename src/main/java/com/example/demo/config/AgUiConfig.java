package com.example.demo.config;

import com.agui.server.spring.AgUiService;
import com.agui.server.streamer.AgentStreamer;
import com.agui.spring.ai.SpringAIAgent;
import com.example.demo.agent.SkillTools;
import com.example.demo.agent.SkillsAdvisor;
import com.example.demo.service.PromptLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

/**
 * AG-UI 协议配置类
 * 负责配置 AG-UI 服务和智能体
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
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(jdbcChatMemoryRepository)
                .maxMessages(20)
                .build();

        // 从 PromptLoader 加载企业智能体系统提示词
        String systemPrompt = promptLoader.getPrompt("prompts/enterprise-agent/system-prompt.template");

        // 创建 SpringAIAgent，复用现有的工具和顾问
        return SpringAIAgent.builder()
                .agentId("enterprise-agent")
                .chatModel(chatModel)
                .systemMessage(systemPrompt)
                .tool(skillTools)
                .advisor(skillsAdvisor)
                .advisor(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
