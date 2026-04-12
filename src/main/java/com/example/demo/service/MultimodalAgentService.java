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
    private final ConversationHistoryService conversationHistoryService;
    private final ChatClient promptGenerationChatClient;

    public MultimodalAgentService(
            AgentService agentService,
            @Qualifier("visionChatClient") ChatClient visionChatClient,
            @Autowired(required = false) TranscriptionModel transcriptionModel,
            PromptLoader promptLoader,
            ConversationHistoryService conversationHistoryService,
            @Qualifier("promptGenerationChatClient") ChatClient promptGenerationChatClient) {
        this.agentService = agentService;
        this.visionChatClient = visionChatClient;
        this.transcriptionModel = transcriptionModel;
        this.promptLoader = promptLoader;
        this.conversationHistoryService = conversationHistoryService;
        this.promptGenerationChatClient = promptGenerationChatClient;
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
        // 检查是否有会话历史
        boolean hasHistory = conversationHistoryService.hasHistory(conversationId);

        if (hasHistory) {
            // 提示词增强流程
            return streamWithPromptEnhancement(query, conversationId, image, imageContentType);
        } else {
            // 直接使用默认提示词流程
            return streamWithDefaultPrompt(query, conversationId, image, imageContentType);
        }
    }

    /**
     * 无会话历史时的默认提示词流程
     */
    private Flux<MultimodalToken> streamWithDefaultPrompt(
            String query, String conversationId,
            Resource image, String imageContentType) {

        String visionPromptTemplate;
        if (query != null && !query.isBlank()) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("{{USER_QUERY}}", query);
            visionPromptTemplate = promptLoader.getPrompt(
                "prompts/multimodal/vision-prompt-with-hint.template", placeholders);
        } else {
            visionPromptTemplate = promptLoader.getPrompt(
                "prompts/multimodal/vision-prompt.template");
        }

        return streamVisionToLlm(visionPromptTemplate, image, imageContentType,
                                query, conversationId);
    }

    /**
     * 有会话历史时的提示词增强流程
     */
    private Flux<MultimodalToken> streamWithPromptEnhancement(
            String query, String conversationId,
            Resource image, String imageContentType) {

        // 1. 获取会话历史摘要
        String historySummary = conversationHistoryService.getRecentHistorySummary(conversationId, 6);

        // 2. 获取默认视图提示词
        String defaultPrompt = promptLoader.getPrompt("prompts/multimodal/vision-prompt.template");

        // 3. 构建生成提示词的 prompt
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{{CONVERSATION_HISTORY}}", historySummary);
        placeholders.put("{{USER_COMMENT}}", query != null ? query : "（无）");
        placeholders.put("{{DEFAULT_VISION_PROMPT}}", defaultPrompt);

        String generatorPrompt = promptLoader.getPrompt(
            "prompts/multimodal/vision-prompt-generator.template", placeholders);

        // 4. 调用语言模型生成情境化提示词（流式）
        // 使用独立的 promptGenerationChatClient，不经过 AgentService 的 advisors
        // 注意：必须使用 .cache() 否则会因为 cold source 导致 LLM 被调用两次
        Flux<String> generatedPromptFlux = promptGenerationChatClient.prompt()
                .user(generatorPrompt)
                .stream()
                .content()
                .cache();

        // 5. 流式返回生成的提示词（type="prompt"）
        // 提示词生成过程实时返回给前端（用于调试/可读性）
        Flux<MultimodalToken> promptStage = generatedPromptFlux
                .map(token -> MultimodalToken.prompt(token));

        // 6. 等待提示词生成完成后，调用公共的 streamVisionToLlm 方法
        Flux<MultimodalToken> visionAndLlmStage = generatedPromptFlux
                .reduce("", String::concat)  // 等待所有提示词 token
                .flatMapMany(generatedPrompt ->
                    streamVisionToLlm(generatedPrompt, image, imageContentType,
                                      query, conversationId));

        // 7. 合并：promptStage (生成过程) + visionAndLlmStage (实际处理)
        return Flux.concat(promptStage, visionAndLlmStage);
    }

    /**
     * 公共方法：使用指定提示词调用视觉模型，然后调用语言模型
     *
     * @param visionPrompt     视图提示词
     * @param image           图片资源
     * @param imageContentType 图片 Content-Type
     * @param query           用户附带的文本（可为 null）
     * @param conversationId  会话 ID
     * @return 包含 vision 和 content 事件的 Flux
     */
    private Flux<MultimodalToken> streamVisionToLlm(
            String visionPrompt,
            Resource image,
            String imageContentType,
            String query,
            String conversationId) {

        MediaType mt = parseMediaType(imageContentType, MediaType.IMAGE_JPEG);
        Media media = new Media(mt, image);

        // 调用视觉模型（流式）
        Flux<String> visionTokens = visionChatClient.prompt()
                .user(user -> user.text(visionPrompt).media(media))
                .stream()
                .content()
                .cache();

        // 视觉模型输出（type="vision"）
        Flux<MultimodalToken> visionStage = visionTokens
                .map(token -> MultimodalToken.vision(token));

        // 等待视觉模型完成，组合输入并调用语言模型
        Flux<MultimodalToken> llmStage = visionTokens
                .reduce("", String::concat)
                .flatMapMany(visionDescription -> {
                    String finalInput = buildFinalInput(visionDescription, query);
                    return agentService.streamChat(finalInput, conversationId)
                            .map(token -> MultimodalToken.content(token));
                });

        return Flux.concat(visionStage, llmStage);
    }

    /**
     * 构建最终输入文本
     */
    private String buildFinalInput(String visionDescription, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptLoader.getLabel("label.image.content", "【图片内容】"))
          .append(visionDescription)
          .append("\n\n");
        if (query != null && !query.isBlank()) {
            sb.append(promptLoader.getLabel("label.user.input", "【用户输入】"))
              .append(query);
        }
        return sb.toString();
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
