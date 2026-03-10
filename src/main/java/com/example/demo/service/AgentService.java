package com.example.demo.service;

import com.example.demo.agent.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.stereotype.Service;

@Service
public class AgentService {

    private final ChatClient chatClient;
    private final SkillTools skillTools;

    public AgentService(ChatClient.Builder builder,
                        SkillTools skillTools,
                        SkillsAdvisor skillsAdvisor,
                        JdbcChatMemoryRepository jdbcChatMemoryRepository) {
        this.skillTools = skillTools;

        // 使用 JDBC 存储，保留最近 20 条消息的窗口
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(jdbcChatMemoryRepository)
                .maxMessages(20)
                .build();

        this.chatClient = builder
            .defaultAdvisors(
                skillsAdvisor,
                MessageChatMemoryAdvisor.builder(chatMemory).build()
            )
            .defaultTools(skillTools)
            .build();
    }

    public String chat(String userMessage) {
        return chat(userMessage, "default");
    }

    public String chat(String userMessage, String conversationId) {
        skillTools.reset();
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }
}
