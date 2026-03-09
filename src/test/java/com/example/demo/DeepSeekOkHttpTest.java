package com.example.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用 OkHttp 直接调用 DeepSeek API 的单元测试
 * 目的：验证问题是否在 Apache HttpClient / RestClient 层
 */
@SpringBootTest
public class DeepSeekOkHttpTest {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testDeepSeekWithOkHttp() throws Exception {
        // 配置 OkHttp 客户端
        OkHttpClient client = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        // 构建请求体
        String jsonBody = """
                {
                  "model": "deepseek-chat",
                  "messages": [
                    {"role": "user", "content": "hello"}
                  ]
                }
                """;

        RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json"));

        // 构建请求
        Request request = new Request.Builder()
                .url(baseUrl + "/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                // 不设置 Accept-Encoding，让 OkHttp 按默认行为处理
                .post(body)
                .build();

        System.out.println("=== OkHttp 测试开始 ===");
        System.out.println("URL: " + request.url());
        System.out.println("发送请求...");

        long startTime = System.currentTimeMillis();

        // 执行请求
        try (Response response = client.newCall(request).execute()) {
            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("响应时间: " + elapsed + "ms");
            System.out.println("HTTP 状态码: " + response.code());
            System.out.println("响应头 Content-Encoding: " + response.header("Content-Encoding"));
            System.out.println("响应头 Transfer-Encoding: " + response.header("Transfer-Encoding"));

            // 获取响应体
            ResponseBody responseBody = response.body();
            assertNotNull(responseBody, "响应体不应为空");

            String responseText = responseBody.string();
            System.out.println("响应体长度: " + responseText.length() + " 字符");
            System.out.println("响应体前 200 字符: " + responseText.substring(0, Math.min(200, responseText.length())));

            // 验证响应
            assertTrue(response.isSuccessful(), "HTTP 请求应成功，但状态码为: " + response.code());
            assertTrue(responseText.contains("\"choices\""), "响应应包含 choices 字段");
            assertTrue(responseText.contains("\"content\""), "响应应包含 content 字段");

            // 解析 JSON
            Map<String, Object> jsonResponse = objectMapper.readValue(responseText, Map.class);
            assertNotNull(jsonResponse.get("id"), "应返回 id");
            assertNotNull(jsonResponse.get("choices"), "应返回 choices");

            System.out.println("=== OkHttp 测试成功 ===");
            System.out.println("结论: OkHttp 可以正常调用 DeepSeek API");
        }
    }
}
