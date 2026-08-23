package com.example.demo.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class SkillToolsTest {

    private SkillRegistry registry;
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private SkillTools tools;

    @BeforeEach
    void setUp() throws Exception {
        registry = new SkillRegistry();
        Field resourceLoader = SkillRegistry.class.getDeclaredField("resourceLoader");
        resourceLoader.setAccessible(true);
        resourceLoader.set(registry, new DefaultResourceLoader());
        registry.init();

        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        tools = new SkillTools(
            registry,
            restTemplate,
            "http://localhost:18080",
            new SkillReferenceReader(registry, new DefaultResourceLoader())
        );
    }

    @Test
    void keepsLoadedSkillsInsideEachToolContext() {
        ToolContext first = toolContext(null);
        ToolContext second = toolContext(null);

        assertThat(tools.loadSkill("search-products", first)).contains("已加载");
        assertThat(tools.loadSkill("search-products", first)).contains("已在本轮加载");
        assertThat(tools.loadSkill("search-products", second))
            .contains("已加载")
            .doesNotContain("已在本轮加载");
    }

    @Test
    void executesOnlyAllowlistedGetAndInjectsTheValidatedToken() {
        String token = "validated-demo-token";
        server.expect(requestTo("http://localhost:18080/api/products?keyword=Sony"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andRespond(withSuccess(
                "[{\"id\":3,\"name\":\"Sony WH-1000XM5\"}]",
                MediaType.APPLICATION_JSON
            ));

        String response = tools.httpRequest(
            "GET",
            "/api/products",
            Map.of(),
            Map.of("keyword", "Sony"),
            Map.of("Accept", "application/json"),
            Map.of(),
            toolContext(token)
        );

        assertThat(response).contains("Sony WH-1000XM5");
        server.verify();
    }

    @Test
    void rejectsDirectWritesUnknownUrlsAndSensitiveHeaders() {
        assertThat(tools.httpRequest(
            "POST", "/api/products/cart", Map.of(), Map.of(), Map.of(),
            Map.of("productId", 3), toolContext("token")
        )).contains("只允许 GET");

        assertThat(tools.httpRequest(
            "GET", "/api/unknown", Map.of(), Map.of(), Map.of(), Map.of(),
            toolContext(null)
        )).contains("不是已注册");

        assertThat(tools.httpRequest(
            "GET", "/api/products", Map.of(), Map.of(),
            Map.of("Authorization", "Bearer attacker"), Map.of(), toolContext(null)
        )).contains("请求头");
    }

    @Test
    void buildsOnlyValidatedMutationMetadataForBrowserConfirmation() {
        String metadata = tools.buildHttpRequest(
            "POST",
            "/api/products/cart",
            Map.of(),
            Map.of("productId", "3"),
            Map.of()
        );

        assertThat(metadata)
            .contains("\"method\":\"POST\"")
            .contains("\"url\":\"http://localhost:18080/api/products/cart\"")
            .contains("\"productId\":\"3\"");
        assertThat(tools.buildHttpRequest(
            "GET", "/api/products", Map.of(), Map.of(), Map.of()
        )).contains("只接受写操作");
    }

    @Test
    void boundsParametersAndSanitizesRemoteErrors() {
        Map<String, String> tooManyQueryParams = new HashMap<>();
        for (int i = 0; i < 33; i++) {
            tooManyQueryParams.put("key" + i, "value");
        }
        assertThat(tools.httpRequest(
            "GET", "/api/products", Map.of(), tooManyQueryParams, Map.of(), Map.of(),
            toolContext(null)
        )).contains("查询参数").contains("上限");

        server.expect(requestTo("http://localhost:18080/api/products"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY)
                .body("upstream-error-" + "x".repeat(20_000))
                .contentType(MediaType.TEXT_PLAIN));

        String response = tools.httpRequest(
            "GET", "/api/products", Map.of(), Map.of(), Map.of(), Map.of(),
            toolContext(null)
        );
        assertThat(response)
            .startsWith("HTTP 502")
            .contains("响应过长已截断")
            .hasSizeLessThan(17_000);
        server.verify();
    }

    private ToolContext toolContext(String token) {
        Map<String, Object> context = new HashMap<>();
        context.put(SkillTools.SKILL_SESSION_CONTEXT_KEY, new SkillLoadSession());
        if (token != null) {
            context.put(SkillTools.AUTH_TOKEN_CONTEXT_KEY, token);
        }
        return new ToolContext(context);
    }
}
