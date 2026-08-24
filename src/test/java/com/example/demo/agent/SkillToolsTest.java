package com.example.demo.agent;

import com.example.demo.config.SkillResourceProperties;
import com.example.demo.model.PendingHttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
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
        SkillResourceCatalog catalog = new SkillResourceCatalog(
            new DefaultResourceLoader(),
            new SkillResourceProperties(List.of("classpath*:skills"))
        );
        registry = new SkillRegistry(catalog);
        registry.init();

        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        tools = new SkillTools(
            registry,
            restTemplate,
            "http://localhost:18080",
            new SkillReferenceReader(registry, catalog)
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
        ToolContext context = toolContext(token);
        tools.loadSkill("search-products", context);
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
            context
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
    void rejectsAllowlistedGetUntilItsOwningSkillIsLoaded() {
        ToolContext context = toolContext(null);

        String response = tools.httpRequest(
            "GET",
            "/api/products",
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            context
        );

        assertThat(response)
            .contains("尚未加载技能 `search-products`")
            .contains("loadSkill(\"search-products\")");
        server.verify();
    }

    @Test
    void rejectsAnApiWhenOnlyAnotherSkillWasLoaded() {
        ToolContext context = toolContext(null);
        tools.loadSkill("get-product-detail", context);

        String response = tools.httpRequest(
            "GET",
            "/api/products",
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            context
        );

        assertThat(response)
            .contains("尚未加载技能 `search-products`")
            .contains("loadSkill(\"search-products\")");
        server.verify();
    }

    @Test
    void buildsOnlyValidatedMutationMetadataForBrowserConfirmation() {
        MutationConfirmationSession confirmationSession = new MutationConfirmationSession();
        ToolContext context = toolContext(null, confirmationSession);
        tools.loadSkill("add-to-cart", context);
        String metadata = tools.buildHttpRequest(
            "POST",
            "/api/products/cart",
            Map.of(),
            Map.of("productId", "3"),
            Map.of(),
            context
        );

        assertThat(metadata)
            .contains("\"method\":\"POST\"")
            .contains("\"url\":\"http://localhost:18080/api/products/cart\"")
            .contains("\"productId\":\"3\"");
        assertThat(confirmationSession.pending())
            .get()
            .extracting(PendingHttpRequest::url)
            .isEqualTo("/api/products/cart");
        assertThat(tools.buildHttpRequest(
            "GET", "/api/products", Map.of(), Map.of(), Map.of(),
            toolContext(null, new MutationConfirmationSession())
        )).contains("只接受写操作");
    }

    @Test
    void rejectsASecondMutationAndKeepsConfirmationContextsIsolated() {
        MutationConfirmationSession firstSession = new MutationConfirmationSession();
        MutationConfirmationSession secondSession = new MutationConfirmationSession();
        ToolContext firstContext = toolContext("token-a", firstSession);
        ToolContext secondContext = toolContext("token-b", secondSession);
        tools.loadSkill("add-to-cart", firstContext);
        tools.loadSkill("checkout", firstContext);
        tools.loadSkill("checkout", secondContext);

        String first = tools.buildHttpRequest(
            "POST",
            "/api/products/cart",
            Map.of(),
            Map.of("productId", "3"),
            Map.of(),
            firstContext
        );
        String duplicate = tools.buildHttpRequest(
            "POST",
            "/api/products/checkout",
            Map.of(),
            Map.of(),
            Map.of(),
            firstContext
        );
        String second = tools.buildHttpRequest(
            "POST",
            "/api/products/checkout",
            Map.of(),
            Map.of(),
            Map.of(),
            secondContext
        );

        assertThat(first).contains("\"url\":\"http://localhost:18080/api/products/cart\"");
        assertThat(duplicate).contains("本轮已经存在待确认");
        assertThat(firstSession.pending()).get()
            .extracting(PendingHttpRequest::url)
            .isEqualTo("/api/products/cart");
        assertThat(second).contains("\"url\":\"http://localhost:18080/api/products/checkout\"");
        assertThat(secondSession.pending()).get()
            .extracting(PendingHttpRequest::url)
            .isEqualTo("/api/products/checkout");
    }

    @Test
    void invalidMutationDoesNotPolluteConfirmationSession() {
        MutationConfirmationSession session = new MutationConfirmationSession();

        assertThat(tools.buildHttpRequest(
            "POST",
            "/api/unknown",
            Map.of(),
            Map.of(),
            Map.of(),
            toolContext(null, session)
        )).contains("不是已注册");
        assertThat(session.pending()).isEmpty();
    }

    @Test
    void rejectsMutationMetadataUntilItsOwningSkillIsLoaded() {
        MutationConfirmationSession session = new MutationConfirmationSession();
        ToolContext context = toolContext(null, session);

        String response = tools.buildHttpRequest(
            "POST",
            "/api/products/cart",
            Map.of(),
            Map.of("productId", "3"),
            Map.of(),
            context
        );

        assertThat(response)
            .contains("尚未加载技能 `add-to-cart`")
            .contains("loadSkill(\"add-to-cart\")");
        assertThat(session.pending()).isEmpty();
    }

    @Test
    void boundsParametersAndSanitizesRemoteErrors() {
        Map<String, String> tooManyQueryParams = new HashMap<>();
        for (int i = 0; i < 33; i++) {
            tooManyQueryParams.put("key" + i, "value");
        }
        ToolContext context = toolContext(null);
        tools.loadSkill("search-products", context);
        assertThat(tools.httpRequest(
            "GET", "/api/products", Map.of(), tooManyQueryParams, Map.of(), Map.of(),
            context
        )).contains("查询参数").contains("上限");

        server.expect(requestTo("http://localhost:18080/api/products"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY)
                .body("upstream-error-" + "x".repeat(20_000))
                .contentType(MediaType.TEXT_PLAIN));

        String response = tools.httpRequest(
            "GET", "/api/products", Map.of(), Map.of(), Map.of(), Map.of(),
            context
        );
        assertThat(response)
            .startsWith("HTTP 502")
            .contains("响应过长已截断")
            .hasSizeLessThan(17_000);
        server.verify();
    }

    @Test
    void rejectsMissingSkillContextInsteadOfExecutingAllowlistedApi() {
        String response = tools.httpRequest(
            "GET",
            "/api/products",
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            null
        );

        assertThat(response).contains("缺少当前请求的 Skill 会话上下文");
        server.verify();
    }

    @Test
    void rejectsAReferenceWhenOnlyAnotherSkillWasLoaded() {
        ToolContext context = toolContext(null);
        tools.loadSkill("search-products", context);

        assertThat(tools.readSkillReference(
            "swagger-petstore-openapi-3-0",
            "operations/addPet.md",
            context
        ))
            .contains("尚未加载技能 `swagger-petstore-openapi-3-0`")
            .contains("loadSkill(\"swagger-petstore-openapi-3-0\")");
    }

    private ToolContext toolContext(String token) {
        return toolContext(token, new MutationConfirmationSession());
    }

    private ToolContext toolContext(
        String token,
        MutationConfirmationSession confirmationSession
    ) {
        Map<String, Object> context = new HashMap<>();
        context.put(SkillTools.SKILL_SESSION_CONTEXT_KEY, new SkillLoadSession());
        context.put(MutationConfirmationSession.CONTEXT_KEY, confirmationSession);
        if (token != null) {
            context.put(SkillTools.AUTH_TOKEN_CONTEXT_KEY, token);
        }
        return new ToolContext(context);
    }
}
