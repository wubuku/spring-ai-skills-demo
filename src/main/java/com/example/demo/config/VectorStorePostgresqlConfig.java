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

    @Value("${spring.ai.vectorstore.pgvector.vector-table-name:vector_store}")
    private String vectorTableName;

    @Value("${spring.ai.vectorstore.pgvector.distance-type:COSINE_DISTANCE}")
    private String distanceType;

    @Value("${spring.ai.vectorstore.pgvector.index-type:HNSW}")
    private String indexType;

    @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}")
    private int dimensions;

    /**
     * 创建 PostgreSQL PgVectorStore Bean (pgvector)
     * 使用 HNSW 索引，支持高效的向量相似度搜索
     */
    @Bean
    public PgVectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
            .vectorTableName(vectorTableName)
            .distanceType(PgVectorStore.PgDistanceType.valueOf(distanceType))
            .indexType(PgVectorStore.PgIndexType.valueOf(indexType))
            .dimensions(dimensions)
            .initializeSchema(true)
            .build();
    }
}
