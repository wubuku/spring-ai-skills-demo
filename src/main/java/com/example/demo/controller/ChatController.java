package com.example.demo.controller;

import com.example.demo.model.ChatMessage;
import com.example.demo.service.AgentService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentService agentService;

    public ChatController(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * 测试端点 - 用于验证 observation logging
     */
    @GetMapping("/test")
    public Map<String, String> test() {
        String response = agentService.chat("你好", "test");
        return Map.of("response", response);
    }

    @PostMapping
    public Map<String, String> chat(
            @RequestBody ChatMessage message,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        // 提取 JWT Token 并设置到 SecurityContext（依赖 INHERITABLETHREADLOCAL 自动传递到子线程）
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String jwt = authHeader.substring(7);
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                    "user",  // principal
                    jwt,     // credentials - 存储原始 JWT
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
                );
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        // 使用 conversationId 区分不同会话，默认为 "default"
        String conversationId = message.getConversationId() != null ?
            message.getConversationId() : "default";
        String response = agentService.chat(message.getContent(), conversationId);
        return Map.of("response", response);
    }
}
