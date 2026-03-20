package com.example.demo.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
public class KnowledgeBaseInitializer {

    private final VectorStore vectorStore;

    public KnowledgeBaseInitializer(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            loadKnowledgeBase();
        } catch (IOException e) {
            log.error("知识库初始化失败", e);
        }
    }

    private void loadKnowledgeBase() throws IOException {
        Path kbPath = Path.of("src/main/resources/knowledge-base");
        if (!Files.exists(kbPath)) {
            log.info("知识库目录不存在");
            return;
        }

        List<Document> documents = Files.walk(kbPath)
            .filter(p -> p.toString().endsWith(".md"))
            .map(this::readFile)
            .filter(d -> d != null)
            .collect(Collectors.toList());

        if (!documents.isEmpty()) {
            vectorStore.add(documents);
            log.info("知识库已加载，共 {} 篇", documents.size());
        }
    }

    private Document readFile(Path file) {
        try {
            String content = Files.readString(file);
            // 使用 UUID 作为文档 ID（兼容 PgVectorStore 等需要 UUID 的 VectorStore）
            // 原始文件名保存在 metadata 中
            String originalId = file.getFileName().toString().replace(".md", "");
            return Document.builder()
                .id(UUID.randomUUID().toString())
                .text(content)
                .metadata(java.util.Map.of(
                    "source", file.toString(),
                    "originalId", originalId
                ))
                .build();
        } catch (IOException e) {
            log.error("读取文件失败: {}", file, e);
            return null;
        }
    }
}