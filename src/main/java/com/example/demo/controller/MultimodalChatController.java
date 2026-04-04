package com.example.demo.controller;

import com.example.demo.dto.MultimodalChatRequest;
import com.example.demo.dto.MultimodalChatResponse;
import com.example.demo.service.AgentService;
import com.example.demo.service.MultimodalAgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

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

    public MultimodalChatController(MultimodalAgentService multimodalAgentService,
                                   AgentService agentService,
                                   ObjectMapper objectMapper) {
        this.multimodalAgentService = multimodalAgentService;
        this.agentService = agentService;
        this.objectMapper = objectMapper;
    }

    /**
     * 多模态聊天入口
     * 支持同时上传文本、图片、音频
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MultimodalChatResponse chat(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "conversationId", required = false, defaultValue = "default") String conversationId,
            @RequestPart(value = "image", required = false) MultipartFile image,
            @RequestPart(value = "audio", required = false) MultipartFile audio
    ) throws Exception {

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
    public MultimodalChatResponse chatText(@RequestBody MultimodalChatRequest request) {
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
            @RequestParam(value = "conversationId", required = false, defaultValue = "default") String conversationId,
            @RequestPart(value = "image", required = false) MultipartFile image,
            @RequestPart(value = "audio", required = false) MultipartFile audio
    ) {

        SseEmitter emitter = new SseEmitter(0L);

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

            Flux<String> tokenFlux = multimodalAgentService.streamChat(
                    query,
                    conversationId,
                    imageResource,
                    imageContentType,
                    audioResource
            );

            // 使用 Reactor 的 Schedulers.boundedElastic() 在独立线程中订阅 Flux
            tokenFlux
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
                    token -> {
                        try {
                            // 使用 OpenAI SSE 格式，兼容多模态扩展
                            // type 字段区分内容来源：
                            // - "vision": 视觉模型识别结果
                            // - "content": LLM 生成内容
                            String type = token.startsWith("【图片识别】") ? "vision" : "content";
                            String content = token.startsWith("【图片识别】")
                                ? token.substring(7)  // 去掉前缀
                                : token;

                            Map<String, Object> delta = Map.of("content", content);
                            Map<String, Object> choice = Map.of("delta", delta);
                            Map<String, Object> chunk = new java.util.HashMap<>();
                            chunk.put("type", type);
                            chunk.put("choices", List.of(choice));
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

        emitter.onTimeout(() -> emitter.complete());
        emitter.onError(e -> emitter.complete());

        return emitter;
    }
}
