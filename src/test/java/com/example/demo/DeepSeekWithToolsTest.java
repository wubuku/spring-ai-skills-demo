package com.example.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试带 Tools 的 DeepSeek API 调用
 */
@SpringBootTest
public class DeepSeekWithToolsTest {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testDeepSeekWithTools() throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        // 模拟 Spring AI 的请求，包含 tools 定义
        Map<String, Object> tool1 = Map.of(
            "type", "function",
            "function", Map.of(
                "name", "loadSkill",
                "description", "加载指定技能的完整操作指令",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "skillName", Map.of("type", "string", "description", "技能名称")
                    ),
                    "required", List.of("skillName")
                )
            )
        );

        Map<String, Object> tool2 = Map.of(
            "type", "function",
            "function", Map.of(
                "name", "httpRequest",
                "description", "发送 HTTP 请求调用 REST API",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "method", Map.of("type", "string", "description", "HTTP 方法"),
                        "url", Map.of("type", "string", "description", "API 路径"),
                        "params", Map.of("type", "object", "description", "Query 参数"),
                        "body", Map.of("type", "object", "description", "请求体")
                    ),
                    "required", List.of("method", "url")
                )
            )
        );

        Map<String, Object> systemMessage = Map.of(
            "role", "system",
            "content", "你是一个智能购物助手。"
        );

        Map<String, Object> userMessage = Map.of(
            "role", "user",
            "content", "帮我找一款3000元以下的耳机"
        );

        Map<String, Object> requestBody = Map.of(
            "model", "deepseek-chat",
            "messages", List.of(systemMessage, userMessage),
            "tools", List.of(tool1, tool2),
            "temperature", 0.7
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        System.out.println("请求体大小: " + jsonBody.length() + " 字符");

        RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json"));

        Request request = new Request.Builder()
                .url(baseUrl + "/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        System.out.println("=== 带 Tools 的测试开始 ===");
        long startTime = System.currentTimeMillis();

        try (Response response = client.newCall(request).execute()) {
            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("响应时间: " + elapsed + "ms");
            System.out.println("HTTP 状态码: " + response.code());
            System.out.println("响应头 Content-Encoding: " + response.header("Content-Encoding"));
            System.out.println("响应头 Transfer-Encoding: " + response.header("Transfer-Encoding"));

            ResponseBody responseBody = response.body();
            assertNotNull(responseBody, "响应体不应为空");

            String responseText = responseBody.string();
            System.out.println("响应体长度: " + responseText.length() + " 字符");
            System.out.println("响应体前 500 字符: " + responseText.substring(0, Math.min(500, responseText.length())));

            assertTrue(response.isSuccessful(), "HTTP 请求应成功，但状态码为: " + response.code());
            assertTrue(responseText.contains("\"choices\""), "响应应包含 choices 字段");

            System.out.println("=== 带 Tools 的测试成功 ===");
        }
    }
}
