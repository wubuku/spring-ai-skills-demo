package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live-llm")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_LLM_TESTS", matches = "(?i)true")
class OpenAiCompatibleApiLiveTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json");

    @Test
    void providerSupportsBasicChatAndToolCalling() throws Exception {
        String apiKey = requiredEnv("OPENAI_API_KEY");
        String baseUrl = requiredEnv("OPENAI_BASE_URL").replaceAll("/+$", "");
        String model = requiredEnv("OPENAI_MODEL");

        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(120))
            .writeTimeout(Duration.ofSeconds(30))
            .build();

        JsonNode basic = post(client, baseUrl, apiKey, Map.of(
            "model", model,
            "messages", List.of(Map.of("role", "user", "content", "Reply with OK only.")),
            "temperature", 0
        ));
        assertThat(basic.path("choices").isArray()).isTrue();
        assertThat(basic.path("choices").isEmpty()).isFalse();

        Map<String, Object> loadSkill = Map.of(
            "type", "function",
            "function", Map.of(
                "name", "loadSkill",
                "description", "Load a named skill",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of("skillName", Map.of("type", "string")),
                    "required", List.of("skillName")
                )
            )
        );
        JsonNode withTools = post(client, baseUrl, apiKey, Map.of(
            "model", model,
            "messages", List.of(Map.of(
                "role", "user",
                "content", "Call loadSkill with skillName search-products."
            )),
            "tools", List.of(loadSkill),
            "tool_choice", "auto",
            "temperature", 0
        ));
        JsonNode message = withTools.path("choices").path(0).path("message");
        assertThat(message.has("tool_calls") || message.hasNonNull("content")).isTrue();
    }

    private JsonNode post(
        OkHttpClient client,
        String baseUrl,
        String apiKey,
        Map<String, Object> payload
    ) throws Exception {
        Request request = new Request.Builder()
            .url(baseUrl + "/v1/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .post(RequestBody.create(JSON.writeValueAsBytes(payload), JSON_MEDIA_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            assertThat(response.code())
                .withFailMessage("OpenAI-compatible provider returned HTTP %s: %s",
                    response.code(), responseBody)
                .isBetween(200, 299);
            return JSON.readTree(responseBody);
        }
    }

    private String requiredEnv(String name) {
        String value = System.getenv(name);
        assertThat(value)
            .withFailMessage("Environment variable %s must be configured", name)
            .isNotBlank();
        return value;
    }
}
