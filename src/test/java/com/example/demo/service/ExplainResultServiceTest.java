package com.example.demo.service;

import com.example.demo.agent.SkillResourceCatalog;
import com.example.demo.agent.SkillRegistry;
import com.example.demo.config.SkillResourceProperties;
import com.example.demo.model.ExplainRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class ExplainResultServiceTest {

    private ChatClient chatClient;
    private ExplainResultService service;

    @BeforeEach
    void setUp() throws Exception {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class);
        when(builder.build()).thenReturn(chatClient);

        SkillResourceCatalog catalog = new SkillResourceCatalog(
            new DefaultResourceLoader(),
            new SkillResourceProperties(List.of("classpath*:skills"))
        );
        SkillRegistry registry = new SkillRegistry(catalog);
        registry.init();

        service = new ExplainResultService(
            builder,
            registry,
            new PromptLoader(new DefaultResourceLoader())
        );
    }

    @Test
    void fallsBackWithSuccessMarkerWhenModelFailsForSuccessfulApiResponse() {
        when(chatClient.prompt()).thenThrow(new IllegalStateException("model unavailable"));

        ExplainRequest request = request("GET", "/api/products", 200, "{\"items\":[]}");

        assertThat(service.explainResult(request))
            .startsWith("✅")
            .contains("GET /api/products")
            .contains("HTTP 200")
            .contains("{\"items\":[]}");
    }

    @Test
    void fallsBackWithFailureMarkerWhenModelFailsForFailedApiResponse() {
        when(chatClient.prompt()).thenThrow(new IllegalStateException("model unavailable"));

        ExplainRequest request = request(
            "POST", "/api/products/cart", 503, "{\"message\":\"service unavailable\"}");

        assertThat(service.explainResult(request))
            .startsWith("❌")
            .contains("POST /api/products/cart")
            .contains("HTTP 503")
            .contains("service unavailable")
            .doesNotContain("操作已完成");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void fallsBackWhenModelReturnsNoContent(String modelContent) {
        stubModelContent(modelContent);

        ExplainRequest request = request("GET", "/api/products", 204, "");

        assertThat(service.explainResult(request))
            .startsWith("✅")
            .contains("HTTP 204");
    }

    @Test
    void usesCurrentSkillCatalogWithoutPromisingUnavailableToolsForUnknownApi() {
        stubModelContent("已生成解释");

        ExplainRequest request = request(
            "GET", "/api/not-registered", 404, "{\"message\":\"not found\"}");
        service.explainResult(request);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(lastRequestSpec()).user(prompt.capture());

        assertThat(prompt.getValue())
            .contains("<available_skills>")
            .contains("search-products")
            .contains("搜索商品目录，支持关键词、分类、价格范围过滤")
            .contains("当前解释客户端只生成说明，不执行任何工具调用")
            .doesNotContain("product-store")
            .doesNotContain("loadSkill");
    }

    @Test
    void keepsFullApiDescriptionForKnownApi() {
        stubModelContent("已生成解释");

        ExplainRequest request = request(
            "GET", "http://localhost:8080/api/products?category=耳机", 200, "[]");
        service.explainResult(request);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(lastRequestSpec()).user(prompt.capture());

        assertThat(prompt.getValue())
            .contains("## API 描述文档")
            .contains("GET /api/products")
            .contains("## 返回结构")
            .contains("Sony WH-1000XM5");
    }

    private ChatClient.ChatClientRequestSpec lastRequestSpec() {
        return requestSpec;
    }

    private ChatClient.ChatClientRequestSpec requestSpec;

    private void stubModelContent(String content) {
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(content);
    }

    private ExplainRequest request(String method, String url, int statusCode, String body) {
        ExplainRequest request = new ExplainRequest();
        request.setMethod(method);
        request.setUrl(url);
        request.setStatusCode(statusCode);
        request.setResponseBody(body);
        return request;
    }
}
