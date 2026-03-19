package com.example.demo.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;

import java.io.File;

/**
 * VectorStore 配置类
 * 使用 SiliconFlow API 提供嵌入模型（OpenAI 兼容格式）
 * 使用 SimpleVectorStore 作为向量存储（内存型，支持文件持久化）
 */
@Configuration
public class VectorStoreConfig {

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
     * Bean 名称为 embeddingModel，会被 VectorStoreChatMemoryAdvisor 自动使用
     */
    @Bean
    public EmbeddingModel embeddingModel() {
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

    /**
     * 创建 VectorStore Bean (SimpleVectorStore)
     * 支持文件持久化
     */
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(embeddingModel)
            .build();

        // 尝试从文件加载已存储的向量
        File vectorStoreFile = new File("./data/vector-store.json");
        if (vectorStoreFile.exists()) {
            try {
                simpleVectorStore.load(new FileSystemResource(vectorStoreFile));
            } catch (Exception e) {
                // 忽略加载错误，使用空存储
            }
        }

        return simpleVectorStore;
    }

    /**
     * VectorStore 持久化控制器
     * 用于在应用关闭时保存向量数据
     */
    @Bean
    public VectorStorePersistenceExecutor vectorStorePersistenceExecutor(VectorStore vectorStore) {
        return new VectorStorePersistenceExecutor(vectorStore);
    }
}