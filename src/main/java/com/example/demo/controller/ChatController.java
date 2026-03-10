package com.example.demo.controller;

import com.example.demo.model.ChatMessage;
import com.example.demo.service.AgentService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentService agentService;

    public ChatController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping
    public Map<String, String> chat(@RequestBody ChatMessage message) {
        // 使用 conversationId 区分不同会话，默认为 "default"
        String conversationId = message.getConversationId() != null ?
            message.getConversationId() : "default";
        String response = agentService.chat(message.getContent(), conversationId);
        return Map.of("response", response);
    }
}
