package com.example.demo.service;

import com.example.demo.model.MultimodalToken;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 多模态代理服务
 *
 * 核心设计：增强现有的 AgentService，而非另起炉灶
 * - 图片理解：使用 visionChatClient 将图片转为文字描述
 * - 语音转写：使用 TranscriptionModel 将语音转为文字
 * - 文字合并：将图片描述、语音转写合并到用户输入
 * - 交给 AgentService.chat() 处理，保留所有现有能力（RAG、Skills等）
 */
@Service
public class MultimodalAgentService {

    private final AgentService agentService;
    private final ChatClient visionChatClient;
    private final TranscriptionModel transcriptionModel;

    public MultimodalAgentService(
            AgentService agentService,
            @Qualifier("visionChatClient") ChatClient visionChatClient,
            @Autowired(required = false) TranscriptionModel transcriptionModel) {
        this.agentService = agentService;
        this.visionChatClient = visionChatClient;
        this.transcriptionModel = transcriptionModel;
    }

    /**
     * 多模态聊天
     * 1. 图片理解 → visionChatClient → 文字描述
     * 2. 语音转写 → TranscriptionModel → 文字
     * 3. 合并所有文字输入
     * 4. 调用 AgentService.chat() 处理（保留所有能力）
     */
    public String chat(String query,
                       String conversationId,
                       Resource image,
                       String imageContentType,
                       Resource audio) {

        StringBuilder enrichedInput = new StringBuilder();

        // 1. 如果有图片，先获取图片的文字描述
        if (image != null) {
            String imageDescription = describeImage(image, imageContentType, query);
            enrichedInput.append("【图片内容】").append(imageDescription).append("\n\n");
        }

        // 2. 如果有音频，转写为文字
        if (audio != null && transcriptionModel != null) {
            String transcription = transcriptionModel.transcribe(audio);
            enrichedInput.append("【语音转写】").append(transcription).append("\n\n");
        } else if (audio != null && transcriptionModel == null) {
            enrichedInput.append("【语音转写】语音转写功能未配置，无法处理音频。\n\n");
        }

        // 3. 如果有用户文本，追加
        if (query != null && !query.isBlank()) {
            enrichedInput.append("【用户输入】").append(query);
        }

        String finalInput = enrichedInput.toString();
        if (finalInput.isBlank()) {
            finalInput = "用户未提供有效输入。";
        }

        // 4. 交给 AgentService 处理（保留所有能力：Skills、RAG、记忆等）
        return agentService.chat(finalInput, conversationId);
    }

    /**
     * 纯文本聊天
     */
    public String chat(String query, String conversationId) {
        return agentService.chat(query, conversationId);
    }

    /**
     * 多模态流式聊天
     */
    public Flux<MultimodalToken> streamChat(String query,
                                            String conversationId,
                                            Resource image,
                                            String imageContentType,
                                            Resource audio) {

        // 路径 1：有图片 - 视觉模型流式 + LLM 流式
        if (image != null) {
            MediaType mt = parseMediaType(imageContentType, MediaType.IMAGE_JPEG);
            Media media = new Media(mt, image);
            String visionPrompt = (query != null && !query.isBlank())
                    ? "用户问题是：" + query + "\n请详细描述这张图片的内容，包括文字、数据、图表、场景等所有重要信息。"
                    : "请详细描述这张图片的内容，包括文字、数据、图表、场景等所有重要信息。";

            Flux<String> visionTokens = visionChatClient.prompt()
                    .user(user -> user.text(visionPrompt).media(media))
                    .stream()
                    .content()
                    .cache();

            Flux<MultimodalToken> visionStage = visionTokens.map(token -> MultimodalToken.vision(token));

            Flux<MultimodalToken> llmStage = visionTokens
                    .reduce("", String::concat)
                    .flatMapMany(imageDescription -> {
                        String finalInput = "【图片内容】" + imageDescription + "\n\n"
                                + (query != null && !query.isBlank() ? "【用户输入】" + query : "");
                        return agentService.streamChat(finalInput, conversationId)
                                .map(token -> MultimodalToken.content(token));
                    });

            return Flux.concat(visionStage, llmStage);
        }

        // 路径 2：有音频（ASR 只能同步）
        if (audio != null && transcriptionModel != null) {
            return Mono.fromCallable(() -> transcriptionModel.transcribe(audio))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(transcript -> {
                        Flux<MultimodalToken> transcribedStage = Flux.just(
                                MultimodalToken.transcribed("【语音转写】" + transcript)
                        );
                        String finalInput = (query != null && !query.isBlank())
                                ? "【用户输入】" + query : "";
                        Flux<MultimodalToken> llmStage = agentService.streamChat(finalInput, conversationId)
                                .map(token -> MultimodalToken.content(token));
                        return Flux.concat(transcribedStage, llmStage);
                    });
        } else if (audio != null && transcriptionModel == null) {
            return Flux.just(MultimodalToken.transcribed("【语音转写】语音转写功能未配置，无法处理音频。\n\n"))
                    .concatWith(query != null && !query.isBlank()
                            ? agentService.streamChat("【用户输入】" + query, conversationId)
                                    .map(token -> MultimodalToken.content(token))
                            : Flux.empty());
        }

        // 路径 3：纯文字
        String finalInput = (query != null && !query.isBlank()) ? query : "用户未提供有效输入。";
        return agentService.streamChat(finalInput, conversationId)
                .map(token -> MultimodalToken.content(token));
    }

    /**
     * 纯文本流式聊天
     */
    public Flux<String> streamChat(String query, String conversationId) {
        return agentService.streamChat(query, conversationId);
    }

    /**
     * 使用视觉模型将图片转换为文字描述
     */
    private String describeImage(Resource image, String imageContentType, String hint) {
        MediaType mt = parseMediaType(imageContentType, MediaType.IMAGE_JPEG);
        Media media = new Media(mt, image);

        String visionPrompt = (hint != null && !hint.isBlank())
                ? "用户问题是：" + hint + "\n请详细描述这张图片的内容，包括文字、数据、图表、场景等所有重要信息。"
                : "请详细描述这张图片的内容，包括文字、数据、图表、场景等所有重要信息。";

        return visionChatClient.prompt()
                .user(user -> user.text(visionPrompt).media(media))
                .call()
                .content();
    }

    private MediaType parseMediaType(String contentType, MediaType defaultType) {
        if (contentType == null || contentType.isBlank()) {
            return defaultType;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            return defaultType;
        }
    }
}
