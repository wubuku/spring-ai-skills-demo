package com.example.demo.controller;

import com.example.demo.model.ChatMessage;
import com.example.demo.service.AgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentService agentService;
    private final ObjectMapper objectMapper;

    public ChatController(AgentService agentService, ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.objectMapper = objectMapper;
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

    @PostMapping(
            path = "/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter chatStream(
            @RequestBody ChatMessage message,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String jwt = authHeader.substring(7);
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                    "user",
                    jwt,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
                );
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        String conversationId = message.getConversationId() != null ?
            message.getConversationId() : "default";

        SseEmitter emitter = new SseEmitter(0L);

        Flux<String> tokenFlux = agentService.streamChat(message.getContent(), conversationId);

        // 使用 Reactor 的 Schedulers.boundedElastic() 在独立线程中订阅 Flux
        tokenFlux
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                token -> {
                    try {
                        Map<String, Object> chunk = Map.of(
                            "choices", List.of(Map.of("delta", Map.of("content", token)))
                        );
                        emitter.send(SseEmitter.event()
                            .data(objectMapper.writeValueAsString(chunk)));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                error -> emitter.completeWithError(error),
                () -> {
                    try {
                        emitter.send(SseEmitter.event().data("[DONE]"));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    } finally {
                        emitter.complete();
                    }
                }
            );

        emitter.onTimeout(() -> emitter.complete());
        emitter.onError(e -> emitter.complete());

        return emitter;
    }
}
