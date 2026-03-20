package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot Application Main Class
 *
 * 默认配置 (H2):
 * - 使用 H2 文件数据库存储 ChatMemory
 * - 使用 SimpleVectorStore (内存+文件持久化) 存储向量
 *
 * PostgreSQL 配置 (profile=postgresql):
 * - 使用 PostgreSQL 数据库存储 ChatMemory
 * - 使用 PgVectorStore (pgvector扩展) 存储向量
 *
 * 切换方式: --spring.profiles.active=postgresql
 */
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
