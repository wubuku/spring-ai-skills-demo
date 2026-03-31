package com.example.demo.controller;

import com.example.demo.dto.MultimodalChatRequest;
import com.example.demo.dto.MultimodalChatResponse;
import com.example.demo.service.AgentService;
import com.example.demo.service.MultimodalAgentService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

/**
 * 多模态聊天控制器
 * 支持图片和音频上传，结合文本进行多模态对话
 *
 * 核心设计：复用现有的 AgentService，保留所有能力（RAG、Skills、记忆等）
 */
@RestController
@RequestMapping("/api/chat")
public class MultimodalChatController {

    private final MultimodalAgentService multimodalAgentService;
    private final AgentService agentService;  // 用于纯文本接口，保持向后兼容

    public MultimodalChatController(MultimodalAgentService multimodalAgentService,
                                   AgentService agentService) {
        this.multimodalAgentService = multimodalAgentService;
        this.agentService = agentService;
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
}
