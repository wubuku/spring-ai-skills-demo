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
        String response = agentService.chat(message.getContent());
        return Map.of("response", response);
    }
}
