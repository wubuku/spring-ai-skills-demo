package com.example.demo.knowledge;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class KnowledgeBaseInitializerTest {

    @Test
    void decodesKnowledgeStreamsAsUtf8() throws Exception {
        String content = "# 保修政策\n\n非人为损坏保修 12 个月。";

        assertThat(KnowledgeBaseInitializer.readUtf8(
            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))
        )).isEqualTo(content);
    }

    @Test
    void importsUtf8KnowledgeOnceInStableSourceOrderWithStableMetadata() {
        VectorStore vectorStore = mock(VectorStore.class);
        KnowledgeBaseInitializer initializer = new KnowledgeBaseInitializer(
            vectorStore,
            new DefaultResourceLoader()
        );
        ReflectionTestUtils.setField(
            initializer,
            "knowledgeBasePaths",
            List.of(
                "classpath:knowledge-base/return-policy.md",
                "classpath:knowledge-base/delivery-info.md",
                "classpath:knowledge-base/return-policy.md"
            )
        );
        ReflectionTestUtils.setField(initializer, "failFast", true);

        initializer.loadKnowledgeBase();
        initializer.loadKnowledgeBase();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).add(documents.capture());

        List<Document> firstLoad = documents.getAllValues().get(0);
        List<Document> secondLoad = documents.getAllValues().get(1);

        assertThat(firstLoad)
            .hasSize(2)
            .extracting(document -> document.getMetadata().get("source").toString())
            .containsExactly(
                "classpath:knowledge-base/delivery-info.md",
                "classpath:knowledge-base/return-policy.md"
            );
        assertThat(secondLoad)
            .extracting(Document::getId)
            .containsExactlyElementsOf(firstLoad.stream().map(Document::getId).toList());

        Document delivery = firstLoad.get(0);
        assertThat(delivery.getText())
            .contains("# 配送说明")
            .contains("偏远地区");
        assertThat(delivery.getMetadata())
            .containsEntry("kind", "knowledge")
            .containsEntry("filename", "delivery-info.md")
            .containsEntry("originalId", "delivery-info")
            .containsEntry("source", "classpath:knowledge-base/delivery-info.md");

        Document returnPolicy = firstLoad.get(1);
        assertThat(returnPolicy.getText())
            .contains("# 退货政策")
            .contains("原路返回支付账户");
        assertThat(returnPolicy.getMetadata())
            .containsEntry("kind", "knowledge")
            .containsEntry("filename", "return-policy.md")
            .containsEntry("originalId", "return-policy")
            .containsEntry("source", "classpath:knowledge-base/return-policy.md");
    }
}
