package com.example.demo.service;

import com.example.demo.agent.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class AgentService {

    private final ChatClient chatClient;
    private final SkillTools skillTools;

    public AgentService(ChatClient.Builder builder,
                        SkillTools skillTools,
                        SkillsAdvisor skillsAdvisor) {
        this.skillTools = skillTools;
        this.chatClient = builder
            .defaultAdvisors(skillsAdvisor)
            .defaultTools(skillTools)
            .build();
    }

    public String chat(String userMessage) {
        skillTools.reset();
        return chatClient.prompt()
            .user(userMessage)
            .call()
            .content();
    }
}
