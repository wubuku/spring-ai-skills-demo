package com.example.demo.knowledge;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class KnowledgeBaseInitializerTest {

    @Test
    void producesStableIdsAndKnowledgeMetadataAcrossRepeatedLoads() {
        VectorStore vectorStore = mock(VectorStore.class);
        KnowledgeBaseInitializer initializer = new KnowledgeBaseInitializer(
            vectorStore,
            new DefaultResourceLoader()
        );
        ReflectionTestUtils.setField(
            initializer,
            "knowledgeBasePaths",
            List.of("classpath:knowledge-base/return-policy.md")
        );
        ReflectionTestUtils.setField(initializer, "failFast", true);

        initializer.loadKnowledgeBase();
        initializer.loadKnowledgeBase();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).add(documents.capture());

        Document first = documents.getAllValues().get(0).get(0);
        Document second = documents.getAllValues().get(1).get(0);
        assertThat(first.getId()).isEqualTo(second.getId());
        assertThat(first.getMetadata())
            .containsEntry("kind", "knowledge")
            .containsEntry("filename", "return-policy.md");
        assertThat(first.getMetadata().get("source").toString())
            .endsWith("knowledge-base/return-policy.md");
    }
}
