package com.example.demo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * EmbeddingModel 配置类
 * 使用 SiliconFlow API 提供嵌入模型（OpenAI 兼容格式）
 *
 * 此配置始终激活（无 profile 限制），因为嵌入模型与存储类型无关
 */
@Slf4j
@Configuration
public class EmbeddingModelConfig {

    @Value("${siliconflow.api-key:}")
    private String siliconFlowApiKey;

    @Value("${siliconflow.base-url:https://api.siliconflow.cn/v1}")
    private String siliconFlowBaseUrl;

    @Value("${siliconflow.model:BAAI/bge-m3}")
    private String siliconFlowModel;

    @Value("${siliconflow.dimensions:1024}")
    private int siliconFlowDimensions;

    /**
     * 创建嵌入模型 Bean
     * 使用 SiliconFlow API（OpenAI 兼容格式）
     * Bean 名称为 embeddingModel，会被 VectorStore 自动使用
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("Creating EmbeddingModel with baseUrl={}, model={}, apiKey={}",
            siliconFlowBaseUrl, siliconFlowModel,
            siliconFlowApiKey != null ? siliconFlowApiKey.substring(0, Math.min(10, siliconFlowApiKey.length())) + "..." : "null");

        OpenAiApi openAiApi = OpenAiApi.builder()
            .baseUrl(siliconFlowBaseUrl)
            .apiKey(siliconFlowApiKey)
            .build();

        OpenAiEmbeddingOptions embeddingOptions = OpenAiEmbeddingOptions.builder()
            .model(siliconFlowModel)
            .dimensions(siliconFlowDimensions)
            .build();

        return new OpenAiEmbeddingModel(openAiApi,
            org.springframework.ai.document.MetadataMode.EMBED,
            embeddingOptions);
    }
}
