package com.example.demo.config;

import com.agui.server.spring.AgUiService;
import com.agui.server.streamer.AgentStreamer;
import com.agui.spring.ai.SpringAIAgent;
import com.example.demo.agent.SkillTools;
import com.example.demo.agent.SkillsAdvisor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
            ChatModel chatModel,
            SkillTools skillTools,
            SkillsAdvisor skillsAdvisor,
            JdbcChatMemoryRepository jdbcChatMemoryRepository
    ) throws Exception {

        // 复用现有的会话记忆配置
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(jdbcChatMemoryRepository)
                .maxMessages(20)
                .build();

        // 创建 SpringAIAgent，复用现有的工具和顾问
        return SpringAIAgent.builder()
                .agentId("enterprise-agent")
                .chatModel(chatModel)
                .systemMessage("""
                    你是企业智能助手，帮助员工解答业务问题、查询数据、执行操作。

                    你可以使用以下能力：
                    1. 技能加载：根据用户需求加载相应的技能模块
                    2. API 调用：通过 httpRequest 工具调用 REST API
                    3. 数据查询：查询商品、订单、员工等信息
                    4. 操作执行：执行各种业务操作（可能需要用户确认）

                    回答要求：
                    - 使用中文，简洁专业
                    - 对于敏感操作，先向用户说明并征得同意
                    - 如果需要查询数据，优先使用提供的工具函数
                    - 按需加载技能，不要一次性加载所有技能

                    **格式化要求：**
                    - 使用 Markdown 格式组织所有回复内容
                    - 使用 **加粗** 强调重要信息
                    - 使用列表（- 或 1.）组织多个要点
                    - 使用代码块（```）展示代码或技术内容
                    - 确保表格列对齐，增强可读性
                    """)
                .tool(skillTools)
                .advisor(skillsAdvisor)
                .chatMemory(chatMemory)
                .build();
    }
}
