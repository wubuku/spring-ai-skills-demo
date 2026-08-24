package com.example.demo;

import com.example.demo.agent.SkillRegistry;
import com.example.demo.auth.AuthFilter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
class BackendApiIntegrationTest {

    private static final int PORT = availablePort();
    private static final List<Prompt> PROMPTS = new CopyOnWriteArrayList<>();
    private String scenario;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("app.api.base-url", () -> "http://localhost:" + PORT);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private SkillRegistry skillRegistry;

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @MockitoBean(name = "openAiChatModel")
    private ChatModel chatModel;

    @MockitoSpyBean
    private AuthFilter authFilter;

    @BeforeEach
    void setUpModel() {
        PROMPTS.clear();
        scenario = "search";
        reset(chatModel);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation ->
            scriptedResponse(invocation.getArgument(0)));
    }

    @Test
    void demoAuthenticationAndProtectedProductApisUseOneTokenContract() throws Exception {
        ResponseEntity<JsonNode> login = rest.postForEntity(
            "/api/auth/login",
            Map.of("username", "user1", "password", "password1"),
            JsonNode.class
        );
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = login.getBody().path("token").asText();
        assertThat(token).isNotBlank();

        HttpHeaders validHeaders = new HttpHeaders();
        validHeaders.setBearerAuth(token);
        clearInvocations(authFilter);
        ResponseEntity<JsonNode> verify = rest.exchange(
            "/api/auth/verify",
            HttpMethod.GET,
            new HttpEntity<>(validHeaders),
            JsonNode.class
        );
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verify.getBody().path("valid").asBoolean()).isTrue();
        verify(authFilter, times(1)).doFilter(
            any(ServletRequest.class),
            any(ServletResponse.class),
            any(FilterChain.class)
        );

        HttpHeaders invalidHeaders = new HttpHeaders();
        invalidHeaders.setBearerAuth(encode("user1:wrong-password"));
        assertThat(rest.exchange(
            "/api/auth/verify",
            HttpMethod.GET,
            new HttpEntity<>(invalidHeaders),
            JsonNode.class
        ).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<JsonNode> cart = rest.exchange(
            "/api/products/cart?productId=3",
            HttpMethod.POST,
            new HttpEntity<>(validHeaders),
            JsonNode.class
        );
        assertThat(cart.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cart.getBody().path("success").asBoolean()).isTrue();

        assertThat(rest.postForEntity(
            "/api/products/cart?productId=3",
            null,
            JsonNode.class
        ).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(rest.postForEntity(
            "/api/products/cart-legacy?userId=1&productId=3",
            null,
            JsonNode.class
        ).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void productAndChatValidationReturnStableHttpErrors() {
        assertThat(rest.getForEntity("/api/products/999", JsonNode.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);

        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> blankChat = rest.postForEntity(
            "/api/chat/text",
            new HttpEntity<>(
                """
                {"query":" ","conversationId":"browser-session"}
                """,
                json
            ),
            JsonNode.class
        );
        assertThat(blankChat.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blankChat.getBody().path("status").asInt()).isEqualTo(400);
    }

    @Test
    void ordinaryChatCompletesARealSpringAiSkillAndHttpToolLoop() {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<JsonNode> response = rest.postForEntity(
            "/api/chat/text",
            new HttpEntity<>(
                """
                {
                  "query":"请列出所有商品",
                  "conversationId":"integration-skill-loop"
                }
                """,
                json
            ),
            JsonNode.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("response").asText())
            .contains("iPhone 15")
            .contains("Sony WH-1000XM5");
        assertThat(PROMPTS).hasSize(3);
        assertThat(PROMPTS.get(0).getSystemMessage().getText())
            .contains("写操作必须调用 `buildHttpRequest`")
            .contains("pathParams")
            .doesNotContain("唯一可用的 HTTP 工具：`httpRequest`（前端执行");
    }

    @Test
    void runtimeSkillApiIndexMatchesSpringMvcHandlersAndSerializesReferences() {
        ResponseEntity<JsonNode> response = rest.getForEntity(
            "/api/agui/skills/api-index", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).hasSize(skillRegistry.getApiIndex().size());

        skillRegistry.getApiIndex().forEach((key, entry) -> {
            JsonNode item = response.getBody().path(key);
            assertThat(item.path("skillName").asText()).as(key)
                .isEqualTo(entry.getSkillName());
            assertThat(item.path("method").asText()).as(key)
                .isEqualTo(entry.getMethod());
            assertThat(item.path("path").asText()).as(key)
                .isEqualTo(entry.getPath());
            if (entry.isHierarchical()) {
                assertThat(item.path("referencePath").asText()).as(key)
                    .isEqualTo(entry.getReferencePath());
            }
            assertThat(hasSpringMvcHandler(entry))
                .as("Skill API index entry must map to a Spring MVC handler: " + key)
                .isTrue();
        });
    }

    @Test
    void ordinaryChatStopsAtMutationConfirmationThenConfirmedApiCompletesCartFlow() {
        scenario = "mutation";
        HttpHeaders authenticatedJson = new HttpHeaders();
        authenticatedJson.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<JsonNode> login = rest.postForEntity(
            "/api/auth/login",
            Map.of("username", "user1", "password", "password1"),
            JsonNode.class
        );
        String token = login.getBody().path("token").asText();
        authenticatedJson.setBearerAuth(token);

        // Clean up state left by another test before starting this deterministic flow.
        rest.exchange(
            "/api/products/checkout",
            HttpMethod.POST,
            new HttpEntity<>(authenticatedJson),
            JsonNode.class
        );

        ResponseEntity<JsonNode> chat = rest.postForEntity(
            "/api/chat/text",
            new HttpEntity<>(
                """
                {
                  "query":"请把商品 3 加入购物车",
                  "conversationId":"integration-mutation-confirmation"
                }
                """,
                authenticatedJson
            ),
            JsonNode.class
        );

        assertThat(chat.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(chat.getBody().path("response").asText())
            .contains("等待用户确认");
        String confirmation = findToolResponse("buildHttpRequest");
        assertThat(confirmation)
            .contains("\"method\":\"POST\"")
            .contains("/api/products/cart")
            .contains("\"productId\":\"3\"");

        ResponseEntity<JsonNode> beforeConfirmation = rest.exchange(
            "/api/products/cart",
            HttpMethod.GET,
            new HttpEntity<>(authenticatedJson),
            JsonNode.class
        );
        assertThat(beforeConfirmation.getBody().path("itemCount").asInt()).isZero();

        ResponseEntity<JsonNode> add = rest.exchange(
            "/api/products/cart?productId=3",
            HttpMethod.POST,
            new HttpEntity<>(authenticatedJson),
            JsonNode.class
        );
        assertThat(add.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(add.getBody().path("success").asBoolean()).isTrue();
        assertThat(add.getBody().path("cartSize").asInt()).isEqualTo(1);

        ResponseEntity<JsonNode> cart = rest.exchange(
            "/api/products/cart",
            HttpMethod.GET,
            new HttpEntity<>(authenticatedJson),
            JsonNode.class
        );
        assertThat(cart.getBody().path("itemCount").asInt()).isEqualTo(1);
        assertThat(cart.getBody().path("items").get(0).path("id").asLong()).isEqualTo(3L);

        ResponseEntity<JsonNode> checkout = rest.exchange(
            "/api/products/checkout",
            HttpMethod.POST,
            new HttpEntity<>(authenticatedJson),
            JsonNode.class
        );
        assertThat(checkout.getBody().path("success").asBoolean()).isTrue();
        assertThat(checkout.getBody().path("itemCount").asInt()).isEqualTo(1);
        assertThat(checkout.getBody().path("totalAmount").asDouble()).isEqualTo(2499.0);

        ResponseEntity<JsonNode> afterCheckout = rest.exchange(
            "/api/products/cart",
            HttpMethod.GET,
            new HttpEntity<>(authenticatedJson),
            JsonNode.class
        );
        assertThat(afterCheckout.getBody().path("itemCount").asInt()).isZero();
    }

    private ChatResponse scriptedResponse(Prompt prompt) {
        if ("mutation".equals(scenario)) {
            return scriptedMutationResponse(prompt);
        }

        PROMPTS.add(prompt.copy());
        Set<String> completedTools = prompt.getInstructions().stream()
            .filter(ToolResponseMessage.class::isInstance)
            .map(ToolResponseMessage.class::cast)
            .flatMap(message -> message.getResponses().stream())
            .map(ToolResponseMessage.ToolResponse::name)
            .collect(java.util.stream.Collectors.toSet());

        if (!completedTools.contains("loadSkill")) {
            return toolCall("call-load-skill", "loadSkill",
                "{\"skillName\":\"search-products\"}");
        }
        if (!completedTools.contains("httpRequest")) {
            return toolCall("call-http-request", "httpRequest", """
                {
                  "method":"GET",
                  "url":"/api/products",
                  "pathParams":{},
                  "queryParams":{},
                  "headers":{},
                  "body":{}
                }
                """);
        }

        String toolData = prompt.getInstructions().stream()
            .filter(ToolResponseMessage.class::isInstance)
            .map(ToolResponseMessage.class::cast)
            .flatMap(message -> message.getResponses().stream())
            .filter(response -> response.name().equals("httpRequest"))
            .map(ToolResponseMessage.ToolResponse::responseData)
            .findFirst()
            .orElse("");
        assertThat(toolData).contains("iPhone 15").contains("Sony WH-1000XM5");
        return new ChatResponse(List.of(new Generation(
            new AssistantMessage("已找到商品：iPhone 15、Sony WH-1000XM5。")
        )));
    }

    private ChatResponse scriptedMutationResponse(Prompt prompt) {
        PROMPTS.add(prompt.copy());
        Set<String> completedTools = completedToolNames(prompt);
        if (!completedTools.contains("loadSkill")) {
            return toolCall("call-load-skill", "loadSkill",
                "{\"skillName\":\"add-to-cart\"}");
        }
        if (!completedTools.contains("buildHttpRequest")) {
            return toolCall("call-build-http-request", "buildHttpRequest", """
                {
                  "method":"POST",
                  "url":"/api/products/cart",
                  "pathParams":{},
                  "queryParams":{"productId":"3"},
                  "body":{}
                }
                """);
        }

        assertThat(findToolResponse(prompt, "buildHttpRequest"))
            .contains("\"method\":\"POST\"")
            .contains("/api/products/cart")
            .contains("\"productId\":\"3\"");
        return new ChatResponse(List.of(new Generation(
            new AssistantMessage("已生成加入购物车请求，等待用户确认。")
        )));
    }

    private Set<String> completedToolNames(Prompt prompt) {
        return prompt.getInstructions().stream()
            .filter(ToolResponseMessage.class::isInstance)
            .map(ToolResponseMessage.class::cast)
            .flatMap(message -> message.getResponses().stream())
            .map(ToolResponseMessage.ToolResponse::name)
            .collect(java.util.stream.Collectors.toSet());
    }

    private String findToolResponse(String toolName) {
        return PROMPTS.stream()
            .map(prompt -> findToolResponse(prompt, toolName))
            .filter(response -> !response.isEmpty())
            .findFirst()
            .orElseThrow();
    }

    private String findToolResponse(Prompt prompt, String toolName) {
        return prompt.getInstructions().stream()
            .filter(ToolResponseMessage.class::isInstance)
            .map(ToolResponseMessage.class::cast)
            .flatMap(message -> message.getResponses().stream())
            .filter(response -> response.name().equals(toolName))
            .map(ToolResponseMessage.ToolResponse::responseData)
            .findFirst()
            .orElse("");
    }

    private boolean hasSpringMvcHandler(SkillRegistry.ApiIndexEntry entry) {
        RequestMethod expectedMethod = RequestMethod.valueOf(entry.getMethod());
        return requestMappingHandlerMapping.getHandlerMethods().keySet().stream()
            .anyMatch(mapping -> mapping.getMethodsCondition().getMethods().contains(expectedMethod)
                && mapping.getPatternValues().contains(entry.getPath()));
    }

    private ChatResponse toolCall(String id, String name, String arguments) {
        AssistantMessage message = AssistantMessage.builder()
            // OpenAI-compatible providers commonly omit assistant content on tool calls.
            .content(null)
            .toolCalls(List.of(new AssistantMessage.ToolCall(
                id, "function", name, arguments
            )))
            .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot allocate test port", e);
        }
    }

    private String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
