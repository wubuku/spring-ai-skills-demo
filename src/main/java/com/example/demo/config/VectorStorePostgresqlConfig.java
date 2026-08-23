package com.example.demo.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL VectorStore 配置类 (使用 pgvector 扩展)
 * 仅在 postgresql profile 激活时生效
 *
 * 使用 PgVectorStore 实现，支持：
 * - 向量相似度搜索
 * - 基于 PostgreSQL 的持久化存储
 * - pgvector 扩展的 HNSW 和 IVFFlat 索引
 */
@Configuration
@Profile("postgresql")
public class VectorStorePostgresqlConfig {

    @Value("${app.ai.vector-store.knowledge-table:vector_store}")
    private String knowledgeTableName;

    @Value("${app.ai.vector-store.chat-memory-table:chat_memory_vector_store}")
    private String chatMemoryTableName;

    @Value("${app.ai.vector-store.distance-type:COSINE_DISTANCE}")
    private String distanceType;

    @Value("${app.ai.vector-store.index-type:HNSW}")
    private String indexType;

    @Value("${app.ai.vector-store.dimensions:1024}")
    private int dimensions;

    @Bean("knowledgeVectorStore")
    public PgVectorStore knowledgeVectorStore(
        JdbcTemplate jdbcTemplate,
        EmbeddingModel embeddingModel
    ) {
        return createStore(jdbcTemplate, embeddingModel, knowledgeTableName);
    }

    @Bean("chatMemoryVectorStore")
    public PgVectorStore chatMemoryVectorStore(
        JdbcTemplate jdbcTemplate,
        EmbeddingModel embeddingModel
    ) {
        return createStore(jdbcTemplate, embeddingModel, chatMemoryTableName);
    }

    private PgVectorStore createStore(
        JdbcTemplate jdbcTemplate,
        EmbeddingModel embeddingModel,
        String tableName
    ) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
            .vectorTableName(tableName)
            .distanceType(PgVectorStore.PgDistanceType.valueOf(distanceType))
            .indexType(PgVectorStore.PgIndexType.valueOf(indexType))
            .dimensions(dimensions)
            .initializeSchema(true)
            .build();
    }
}
