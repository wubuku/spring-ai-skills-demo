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

import java.util.HashMap;
import java.util.Map;

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
    private final PromptLoader promptLoader;

    public MultimodalAgentService(
            AgentService agentService,
            @Qualifier("visionChatClient") ChatClient visionChatClient,
            @Autowired(required = false) TranscriptionModel transcriptionModel,
            PromptLoader promptLoader) {
        this.agentService = agentService;
        this.visionChatClient = visionChatClient;
        this.transcriptionModel = transcriptionModel;
        this.promptLoader = promptLoader;
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
            enrichedInput.append(promptLoader.getLabel("label.image.content", "【图片内容】"))
                    .append(imageDescription).append("\n\n");
        }

        // 2. 如果有音频，转写为文字
        if (audio != null && transcriptionModel != null) {
            String transcription = transcriptionModel.transcribe(audio);
            enrichedInput.append(promptLoader.getLabel("label.audio.transcribed", "【语音转写】"))
                    .append(transcription).append("\n\n");
        } else if (audio != null && transcriptionModel == null) {
            enrichedInput.append(promptLoader.getLabel("label.audio.not.configured", "【语音转写】语音转写功能未配置，无法处理音频。")).append("\n\n");
        }

        // 3. 如果有用户文本，追加
        if (query != null && !query.isBlank()) {
            enrichedInput.append(promptLoader.getLabel("label.user.input", "【用户输入】")).append(query);
        }

        String finalInput = enrichedInput.toString();
        if (finalInput.isBlank()) {
            finalInput = promptLoader.getLabel("label.no.input", "用户未提供有效输入。");
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
     *
     * 支持：纯文字、图片+文字、语音+文字、图片+语音+文字
     * 每个模态都尽可能流式处理
     */
    public Flux<MultimodalToken> streamChat(String query,
                                            String conversationId,
                                            Resource image,
                                            String imageContentType,
                                            Resource audio) {

        boolean hasImage = image != null;
        boolean hasAudio = audio != null;

        // 场景 1：图片 + 语音
        if (hasImage && hasAudio) {
            return streamImageAndAudio(query, conversationId, image, imageContentType, audio);
        }

        // 场景 2：只有图片
        if (hasImage) {
            return streamImageOnly(query, conversationId, image, imageContentType);
        }

        // 场景 3：只有音频
        if (hasAudio) {
            return streamAudioOnly(query, conversationId, audio);
        }

        // 场景 4：纯文字
        String finalInput = (query != null && !query.isBlank()) ? query : promptLoader.getLabel("label.no.input", "用户未提供有效输入。");
        return agentService.streamChat(finalInput, conversationId)
                .map(token -> MultimodalToken.content(token));
    }

    private Flux<MultimodalToken> streamImageOnly(String query, String conversationId,
                                                  Resource image, String imageContentType) {
        MediaType mt = parseMediaType(imageContentType, MediaType.IMAGE_JPEG);
        Media media = new Media(mt, image);

        String visionPromptTemplate;
        if (query != null && !query.isBlank()) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("{{USER_QUERY}}", query);
            visionPromptTemplate = promptLoader.getPrompt("prompts/multimodal/vision-prompt-with-hint.template", placeholders);
        } else {
            visionPromptTemplate = promptLoader.getPrompt("prompts/multimodal/vision-prompt.template");
        }

        Flux<String> visionTokens = visionChatClient.prompt()
                .user(user -> user.text(visionPromptTemplate).media(media))
                .stream()
                .content()
                .cache();

        Flux<MultimodalToken> visionStage = visionTokens.map(token -> MultimodalToken.vision(token));

        Flux<MultimodalToken> llmStage = visionTokens
                .reduce("", String::concat)
                .flatMapMany(imageDescription -> {
                    String finalInput = promptLoader.getLabel("label.image.content", "【图片内容】") + imageDescription + "\n\n"
                            + (query != null && !query.isBlank() ? promptLoader.getLabel("label.user.input", "【用户输入】") + query : "");
                    return agentService.streamChat(finalInput, conversationId)
                            .map(token -> MultimodalToken.content(token));
                });

        return Flux.concat(visionStage, llmStage);
    }

    private Flux<MultimodalToken> streamAudioOnly(String query, String conversationId, Resource audio) {
        if (transcriptionModel == null) {
            return Flux.just(MultimodalToken.transcribed(promptLoader.getLabel("label.audio.not.configured", "【语音转写】语音转写功能未配置，无法处理音频。") + "\n\n"))
                    .concatWith(query != null && !query.isBlank()
                            ? agentService.streamChat(promptLoader.getLabel("label.user.input", "【用户输入】") + query, conversationId)
                                    .map(token -> MultimodalToken.content(token))
                            : Flux.empty());
        }

        return Mono.fromCallable(() -> transcriptionModel.transcribe(audio))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(transcript -> {
                    String finalInput = promptLoader.getLabel("label.audio.transcribed", "【语音转写】") + transcript + "\n\n"
                            + (query != null && !query.isBlank() ? promptLoader.getLabel("label.user.input", "【用户输入】") + query : "");
                    return agentService.streamChat(finalInput, conversationId)
                            .map(token -> MultimodalToken.content(token));
                });
    }

    private Flux<MultimodalToken> streamImageAndAudio(String query, String conversationId,
                                                       Resource image, String imageContentType,
                                                       Resource audio) {
        MediaType mt = parseMediaType(imageContentType, MediaType.IMAGE_JPEG);
        Media media = new Media(mt, image);
        String visionPromptTemplate = promptLoader.getPrompt("prompts/multimodal/vision-prompt.template");

        // Vision 流式处理
        Flux<String> visionTokens = visionChatClient.prompt()
                .user(user -> user.text(visionPromptTemplate).media(media))
                .stream()
                .content()
                .cache();

        Flux<MultimodalToken> visionStage = visionTokens.map(token -> MultimodalToken.vision(token));

        // ASR 同步处理（与 vision 并行）
        Mono<String> asrMono = transcriptionModel != null
                ? Mono.fromCallable(() -> transcriptionModel.transcribe(audio))
                        .subscribeOn(Schedulers.boundedElastic())
                : Mono.just(promptLoader.getLabel("label.audio.not.configured", "【语音转写】语音转写功能未配置，无法处理音频。"));

        // LLM 需要等 vision 和 ASR 都完成
        Flux<MultimodalToken> llmStage = visionTokens
                .reduce("", String::concat)
                .zipWith(asrMono)
                .flatMapMany(tuple -> {
                    String imageDescription = tuple.getT1();
                    String transcript = tuple.getT2();
                    String finalInput = promptLoader.getLabel("label.image.content", "【图片内容】") + imageDescription + "\n\n"
                            + promptLoader.getLabel("label.audio.transcribed", "【语音转写】") + transcript + "\n\n"
                            + (query != null && !query.isBlank() ? promptLoader.getLabel("label.user.input", "【用户输入】") + query : "");
                    return agentService.streamChat(finalInput, conversationId)
                            .map(token -> MultimodalToken.content(token));
                });

        return Flux.concat(visionStage, llmStage);
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

        String visionPromptTemplate;
        if (hint != null && !hint.isBlank()) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("{{USER_QUERY}}", hint);
            visionPromptTemplate = promptLoader.getPrompt("prompts/multimodal/vision-prompt-with-hint.template", placeholders);
        } else {
            visionPromptTemplate = promptLoader.getPrompt("prompts/multimodal/vision-prompt.template");
        }

        return visionChatClient.prompt()
                .user(user -> user.text(visionPromptTemplate).media(media))
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
