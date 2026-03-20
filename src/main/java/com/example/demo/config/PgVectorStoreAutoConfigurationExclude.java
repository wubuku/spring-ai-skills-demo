package com.example.demo.config;

import org.springframework.context.annotation.Profile;

/**
 * PostgreSQL VectorStore 自动配置排除配置类
 *
 * 问题: spring-ai-starter-vector-store-pgvector 依赖在添加后会自动配置 PgVectorStore，
 *       即使在没有激活 postgresql profile 的情况下也会尝试连接 PostgreSQL。
 *
 * 解决方案: 通过 application.yml 中的 spring.ai.vectorstore.pgvector.enabled=false 属性禁用
 *
 * 注意: 此类仅作为注释用途，实际的禁用是通过 application.yml 中的配置实现的
 */
@Profile("!postgresql")
public class PgVectorStoreAutoConfigurationExclude {
    // 此类使用 @Profile("!postgresql") 注解标记
    // 当 postgresql profile 未激活时生效
}
