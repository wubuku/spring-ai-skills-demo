package com.example.demo.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;

import java.io.File;

/**
 * VectorStore 配置类（默认/H2 实现）
 *
 * 使用 SimpleVectorStore 作为向量存储（内存型，支持文件持久化）
 *
 * 注意：此配置仅在非 postgresql profile 时激活
 */
@Configuration
@Profile("!postgresql")
public class VectorStoreConfig {

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
     * 用于在应用关闭时保存向量数据到文件
     * 注意：此控制器仅在非 postgresql profile 时生效
     */
    @Bean
    public VectorStorePersistenceExecutor vectorStorePersistenceExecutor(VectorStore vectorStore) {
        return new VectorStorePersistenceExecutor(vectorStore);
    }
}
