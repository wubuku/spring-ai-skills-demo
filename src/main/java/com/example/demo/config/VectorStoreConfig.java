package com.example.demo.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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

    @Bean("knowledgeVectorStore")
    public VectorStore knowledgeVectorStore(
        EmbeddingModel embeddingModel,
        @Value("${app.ai.vector-store.knowledge-file:./data/vector-store.json}")
        String persistenceFile
    ) {
        return simpleVectorStore(embeddingModel, persistenceFile);
    }

    @Bean("chatMemoryVectorStore")
    public VectorStore chatMemoryVectorStore(
        EmbeddingModel embeddingModel,
        @Value("${app.ai.vector-store.chat-memory-file:./data/chat-memory-vector-store.json}")
        String persistenceFile
    ) {
        return simpleVectorStore(embeddingModel, persistenceFile);
    }

    @Bean
    public VectorStorePersistenceExecutor knowledgeVectorStorePersistenceExecutor(
        @Qualifier("knowledgeVectorStore") VectorStore vectorStore,
        @Value("${app.ai.vector-store.knowledge-file:./data/vector-store.json}")
        String persistenceFile
    ) {
        return new VectorStorePersistenceExecutor(vectorStore, persistenceFile);
    }

    @Bean
    public VectorStorePersistenceExecutor chatMemoryVectorStorePersistenceExecutor(
        @Qualifier("chatMemoryVectorStore") VectorStore vectorStore,
        @Value("${app.ai.vector-store.chat-memory-file:./data/chat-memory-vector-store.json}")
        String persistenceFile
    ) {
        return new VectorStorePersistenceExecutor(vectorStore, persistenceFile);
    }

    private VectorStore simpleVectorStore(
        EmbeddingModel embeddingModel,
        String persistenceFile
    ) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(embeddingModel)
            .build();

        File vectorStoreFile = new File(persistenceFile);
        if (vectorStoreFile.exists()) {
            try {
                simpleVectorStore.load(new FileSystemResource(vectorStoreFile));
            } catch (Exception e) {
                throw new IllegalStateException(
                    "无法加载向量存储文件: " + vectorStoreFile.getAbsolutePath(), e);
            }
        }

        return simpleVectorStore;
    }
}
