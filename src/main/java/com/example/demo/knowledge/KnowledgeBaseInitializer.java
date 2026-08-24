package com.example.demo.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.rag.enabled", havingValue = "true")
public class KnowledgeBaseInitializer {

    private final VectorStore vectorStore;
    private final ResourceLoader resourceLoader;

    /**
     * 知识库路径列表，支持类路径（classpath:）和文件系统路径（file:）
     * 例如：
     *   - classpath:knowledge-base/*.md
     *   - file:/opt/knowledge/*.md
     */
    @Value("${knowledge-base.paths:classpath:knowledge-base/*.md}")
    private List<String> knowledgeBasePaths;

    @Value("${knowledge-base.fail-fast:false}")
    private boolean failFast;

    public KnowledgeBaseInitializer(
        @Qualifier("knowledgeVectorStore") VectorStore vectorStore,
        ResourceLoader resourceLoader
    ) {
        this.vectorStore = vectorStore;
        this.resourceLoader = resourceLoader;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            loadKnowledgeBase();
        } catch (Exception e) {
            if (failFast) {
                throw e;
            }
            log.error("知识库初始化失败", e);
        }
    }

    void loadKnowledgeBase() {
        Map<String, Document> documentsBySource = new LinkedHashMap<>();
        int duplicateCount = 0;
        
        log.info("开始加载知识库，配置的路径列表: {}", knowledgeBasePaths);
        
        for (String pathPattern : knowledgeBasePaths) {
            try {
                List<Document> documents = loadFromPath(pathPattern);
                for (Document document : documents) {
                    String source = document.getMetadata().get("source").toString();
                    if (documentsBySource.putIfAbsent(source, document) != null) {
                        duplicateCount++;
                    }
                }
                log.info("从路径 [{}] 加载了 {} 个文档", pathPattern, documents.size());
            } catch (Exception e) {
                if (failFast) {
                    throw new IllegalStateException(
                        "从路径加载知识库失败: " + pathPattern, e);
                }
                log.warn("从路径 [{}] 加载知识库失败: {}", pathPattern, e.getMessage());
            }
        }

        List<Document> allDocuments = documentsBySource.values().stream()
            .sorted(Comparator.comparing(
                document -> document.getMetadata().get("source").toString()
            ))
            .toList();

        if (!allDocuments.isEmpty()) {
            vectorStore.add(allDocuments);
            log.info("知识库加载完成，共 {} 篇文档，忽略 {} 个重复 source",
                allDocuments.size(), duplicateCount);
        } else {
            log.warn("知识库为空，未加载任何文档");
        }
    }

    private List<Document> loadFromPath(String pathPattern) throws IOException {
        List<Document> documents = new ArrayList<>();
        
        // 使用 ResourceLoader 加载资源（支持 classpath: 和 file: 等多种协议）
        Resource[] resources = org.springframework.core.io.support.ResourcePatternUtils
            .getResourcePatternResolver(resourceLoader)
            .getResources(pathPattern);
        
        if (resources.length == 0) {
            log.debug("路径 [{}] 未找到任何资源", pathPattern);
            return documents;
        }
        
        for (Resource resource : resources) {
            if (!resource.exists()) {
                continue;
            }
            
            try {
                Document doc = readResource(resource);
                if (doc != null) {
                    documents.add(doc);
                }
            } catch (IOException e) {
                if (failFast) {
                    throw e;
                }
                log.error("读取资源失败: {} - {}", resource, e.getMessage());
            }
        }
        
        return documents;
    }

    private Document readResource(Resource resource) throws IOException {
        String content;
        try (InputStream input = resource.getInputStream()) {
            content = readUtf8(input);
        }
        
        if (content.trim().isEmpty()) {
            log.warn("资源内容为空: {}", resource);
            return null;
        }
        
        String filename = resource.getFilename();
        String originalId = filename != null ? filename.replace(".md", "") : "unknown";
        String source = normalizedSource(resource);
        String stableId = UUID.nameUUIDFromBytes(
            source.getBytes(StandardCharsets.UTF_8)
        ).toString();
        
        return Document.builder()
            .id(stableId)
            .text(content)
            .metadata(Map.of(
                "kind", "knowledge",
                "source", source,
                "originalId", originalId,
                "filename", filename != null ? filename : "unknown"
            ))
            .build();
    }

    static String readUtf8(InputStream input) throws IOException {
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(input, StandardCharsets.UTF_8)
        );
        return reader.lines().collect(Collectors.joining("\n"));
    }

    private String normalizedSource(Resource resource) throws IOException {
        String source = resource.getURI().normalize().toString().replace('\\', '/');
        int jarSeparator = source.indexOf("!/");
        if (jarSeparator >= 0) {
            return "classpath:" + source.substring(jarSeparator + 2);
        }
        for (String marker : List.of(
            "/target/classes/",
            "/target/test-classes/",
            "/src/main/resources/"
        )) {
            int markerIndex = source.indexOf(marker);
            if (markerIndex >= 0) {
                return "classpath:" + source.substring(markerIndex + marker.length());
            }
        }
        return source;
    }
}
