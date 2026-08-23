package com.example.demo.controller;

import com.example.demo.dto.MultimodalChatRequest;
import com.example.demo.dto.MultimodalChatResponse;
import com.example.demo.model.MultimodalToken;
import com.example.demo.service.AgentService;
import com.example.demo.service.ConversationIdResolver;
import com.example.demo.service.MultimodalAgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 多模态聊天控制器
 * 支持图片和音频上传，结合文本进行多模态对话
 *
 * 核心设计：复用现有的 AgentService，保留所有能力（RAG、Skills、记忆等）
 * SSE 格式：兼容 OpenAI 标准格式，支持多模态扩展（通过 type 字段区分）
 */
@RestController
@RequestMapping("/api/chat")
public class MultimodalChatController {

    private final MultimodalAgentService multimodalAgentService;
    private final AgentService agentService;
    private final ObjectMapper objectMapper;
    private final ConversationIdResolver conversationIdResolver;
    private final long streamTimeoutMs;

    public MultimodalChatController(MultimodalAgentService multimodalAgentService,
                                   AgentService agentService,
                                   ObjectMapper objectMapper,
                                   ConversationIdResolver conversationIdResolver,
                                   @Value("${app.ai.stream-timeout-ms:180000}")
                                   long streamTimeoutMs) {
        this.multimodalAgentService = multimodalAgentService;
        this.agentService = agentService;
        this.objectMapper = objectMapper;
        this.conversationIdResolver = conversationIdResolver;
        this.streamTimeoutMs = streamTimeoutMs;
    }

    /**
     * 多模态聊天入口
     * 支持同时上传文本、图片、音频
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MultimodalChatResponse chat(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "conversationId", required = false) String conversationId,
            @RequestPart(value = "image", required = false) MultipartFile image,
            @RequestPart(value = "audio", required = false) MultipartFile audio,
            Authentication authentication
    ) throws Exception {
        validateMultipartInput(query, image, audio);
        conversationId = conversationIdResolver.resolve(conversationId, authentication);

        File imageTemp = null;
        File audioTemp = null;

        try {
            // 处理图片
            FileSystemResource imageResource = null;
            String imageContentType = null;
            if (image != null && !image.isEmpty()) {
                imageTemp = File.createTempFile("img-", "-" + image.getOriginalFilename());
                image.transferTo(imageTemp);
                imageResource = new FileSystemResource(imageTemp);
                imageContentType = image.getContentType();
            }

            // 处理音频
            FileSystemResource audioResource = null;
            if (audio != null && !audio.isEmpty()) {
                audioTemp = File.createTempFile("audio-", "-" + audio.getOriginalFilename());
                audio.transferTo(audioTemp);
                audioResource = new FileSystemResource(audioTemp);
            }

            // 调用多模态服务处理
            String answer = multimodalAgentService.chat(
                    query,
                    conversationId,
                    imageResource,
                    imageContentType,
                    audioResource
            );

            return new MultimodalChatResponse(answer);

        } finally {
            // 清理临时文件
            if (imageTemp != null && imageTemp.exists()) {
                imageTemp.delete();
            }
            if (audioTemp != null && audioTemp.exists()) {
                audioTemp.delete();
            }
        }
    }

    /**
     * 纯文本聊天（直接使用现有的 AgentService，保持向后兼容）
     */
    @PostMapping(path = "/text", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MultimodalChatResponse chatText(
        @Valid @RequestBody MultimodalChatRequest request
    ) {
        String answer = agentService.chat(
                request.query(),
                request.conversationId()
        );
        return new MultimodalChatResponse(answer);
    }

    /**
     * 多模态流式聊天入口
     * 支持 text + image + audio，返回 SSE 流式响应
     */
    @PostMapping(
            path = "/multimodal/stream",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter chatStream(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "conversationId", required = false) String conversationId,
            @RequestPart(value = "image", required = false) MultipartFile image,
            @RequestPart(value = "audio", required = false) MultipartFile audio,
            Authentication authentication
    ) {
        validateMultipartInput(query, image, audio);
        conversationId = conversationIdResolver.resolve(conversationId, authentication);

        SseEmitter emitter = new SseEmitter(streamTimeoutMs);
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        File imageTemp = null;
        File audioTemp = null;

        try {
            FileSystemResource imageResource = null;
            String imageContentType = null;
            if (image != null && !image.isEmpty()) {
                imageTemp = File.createTempFile("img-", "-" + image.getOriginalFilename());
                image.transferTo(imageTemp);
                imageResource = new FileSystemResource(imageTemp);
                imageContentType = image.getContentType();
            }

            FileSystemResource audioResource = null;
            if (audio != null && !audio.isEmpty()) {
                audioTemp = File.createTempFile("audio-", "-" + audio.getOriginalFilename());
                audio.transferTo(audioTemp);
                audioResource = new FileSystemResource(audioTemp);
            }

            final File finalImageTemp = imageTemp;
            final File finalAudioTemp = audioTemp;

            Flux<MultimodalToken> tokenFlux = multimodalAgentService.streamChat(
                    query,
                    conversationId,
                    imageResource,
                    imageContentType,
                    audioResource
            );

            Disposable subscription = tokenFlux
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signalType -> {
                    if (finalImageTemp != null && finalImageTemp.exists()) {
                        finalImageTemp.delete();
                    }
                    if (finalAudioTemp != null && finalAudioTemp.exists()) {
                        finalAudioTemp.delete();
                    }
                })
                .subscribe(
                    mt -> {
                        try {
                            Map<String, Object> chunk = Map.of(
                                "type", mt.type(),
                                "choices", List.of(Map.of("delta", Map.of("content", mt.content())))
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
            subscriptionRef.set(subscription);

        } catch (Exception e) {
            // 如果文件处理阶段就出错，清理临时文件并返回错误
            if (imageTemp != null && imageTemp.exists()) {
                imageTemp.delete();
            }
            if (audioTemp != null && audioTemp.exists()) {
                audioTemp.delete();
            }
            emitter.completeWithError(e);
        }

        emitter.onCompletion(() -> dispose(subscriptionRef));
        emitter.onTimeout(() -> {
            dispose(subscriptionRef);
            emitter.complete();
        });
        emitter.onError(e -> dispose(subscriptionRef));

        return emitter;
    }

    private void validateMultipartInput(
        String query,
        MultipartFile image,
        MultipartFile audio
    ) {
        if (query != null && query.length() > 8192) {
            throw new IllegalArgumentException("query 长度不能超过 8192");
        }
        boolean hasText = query != null && !query.isBlank();
        boolean hasImage = image != null && !image.isEmpty();
        boolean hasAudio = audio != null && !audio.isEmpty();
        if (!hasText && !hasImage && !hasAudio) {
            throw new IllegalArgumentException("必须提供文本、图片或音频");
        }
    }

    private void dispose(AtomicReference<Disposable> subscriptionRef) {
        Disposable subscription = subscriptionRef.getAndSet(null);
        if (subscription != null) {
            subscription.dispose();
        }
    }
}
