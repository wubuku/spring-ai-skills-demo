package com.example.demo;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("container")
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("postgresql")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "app.ai.rag.enabled=false",
    "app.ai.vector-memory.enabled=false",
    "knowledge-base.enabled=false"
})
class PostgresqlProfileIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.ai.chat.memory.repository.jdbc.platform", () -> "postgresql");
        registry.add("spring.ai.chat.memory.repository.jdbc.initialize-schema", () -> "always");
    }

    @MockitoBean(name = "openAiChatModel")
    private ChatModel chatModel;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("knowledgeVectorStore")
    private VectorStore knowledgeVectorStore;

    @Autowired
    @Qualifier("chatMemoryVectorStore")
    private VectorStore chatMemoryVectorStore;

    @Test
    void initializesChatMemoryAndIndependentVectorTables() {
        List<String> tables = jdbcTemplate.queryForList(
            """
            select table_name
            from information_schema.tables
            where table_schema = 'public'
            """,
            String.class
        );

        assertThat(tables)
            .contains("spring_ai_chat_memory")
            .contains("vector_store")
            .contains("chat_memory_vector_store");
        assertThat(knowledgeVectorStore).isNotSameAs(chatMemoryVectorStore);
    }
}
