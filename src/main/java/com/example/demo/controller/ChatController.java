package com.example.demo.controller;

import com.example.demo.model.ChatMessage;
import com.example.demo.service.AgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentService agentService;
    private final ObjectMapper objectMapper;
    private final long streamTimeoutMs;

    public ChatController(
        AgentService agentService,
        ObjectMapper objectMapper,
        @Value("${app.ai.stream-timeout-ms:180000}") long streamTimeoutMs
    ) {
        this.agentService = agentService;
        this.objectMapper = objectMapper;
        this.streamTimeoutMs = streamTimeoutMs;
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
            @Valid @RequestBody ChatMessage message
    ) {
        String response = agentService.chat(
            message.getContent(),
            message.getConversationId()
        );
        return Map.of("response", response);
    }

    @PostMapping(
            path = "/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter chatStream(
            @Valid @RequestBody ChatMessage message
    ) {
        SseEmitter emitter = new SseEmitter(streamTimeoutMs);

        Flux<String> tokenFlux = agentService.streamChat(
            message.getContent(),
            message.getConversationId()
        );

        Disposable subscription = tokenFlux
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

        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> {
            subscription.dispose();
            emitter.complete();
        });
        emitter.onError(e -> subscription.dispose());

        return emitter;
    }
}
