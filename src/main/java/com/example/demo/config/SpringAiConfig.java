package com.example.demo.config;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.*;
import okio.Buffer;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Spring AI HTTP 配置
 * 使用 OkHttp 驱动的 RestClient
 */
@Configuration
public class SpringAiConfig {

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.model}")
    private String model;

    @Value("${spring.ai.openai.chat.options.temperature}")
    private Double temperature;

    @Bean
    @Primary
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .protocols(Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    // 这条日志出现就证明请求走的是 OkHttp
                    System.out.println("[OkHttp] --> " + request.url());

                    // 重试逻辑：处理 DeepSeek API 的间歇性 EOF 错误
                    int maxRetries = 3;
                    Exception lastException = null;

                    // 预先缓存请求体，以便重试
                    byte[] requestBodyBytes = null;
                    if (request.body() != null) {
                        Buffer buffer = new Buffer();
                        try {
                            request.body().writeTo(buffer);
                            requestBodyBytes = buffer.readByteArray();
                        } catch (Exception e) {
                            // ignore
                        }
                    }

                    for (int i = 0; i <= maxRetries; i++) {
                        try {
                            // 构建请求
                            Request retryRequest = request;
                            if (i > 0 && requestBodyBytes != null) {
                                retryRequest = request.newBuilder()
                                    .post(RequestBody.create(request.body().contentType(), requestBodyBytes))
                                    .build();
                            }

                            Response response = chain.proceed(retryRequest);
                            System.out.println("[OkHttp] <-- " + response.code()
                                    + " Content-Encoding:" + response.header("Content-Encoding")
                                    + " Transfer-Encoding:" + response.header("Transfer-Encoding"));

                            // 尝试读取并缓冲响应体，如果失败则重试
                            String responseBody = response.body().string();
                            System.out.println("[OkHttp] Response body length: " + responseBody.length());

                            // 返回新的响应（使用缓冲的 body）
                            return response.newBuilder()
                                .body(ResponseBody.create(response.body().contentType(), responseBody))
                                .build();
                        } catch (java.io.EOFException e) {
                            lastException = e;
                            System.out.println("[OkHttp] EOFException on attempt " + (i + 1) + "/" + (maxRetries + 1));
                            if (i < maxRetries) {
                                try { Thread.sleep(1000 * (i + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                            }
                        }
                    }
                    throw new RuntimeException("OkHttp retry exhausted", lastException);
                })
                .build();
    }

    @Bean
    @Primary
    @SuppressWarnings("deprecation")
    public ClientHttpRequestFactory clientHttpRequestFactory(OkHttpClient okHttpClient) {
        return new org.springframework.http.client.OkHttp3ClientHttpRequestFactory(okHttpClient);
    }

    @Bean
    @Primary
    public RestClient.Builder restClientBuilder(ClientHttpRequestFactory clientHttpRequestFactory) {
        return RestClient.builder()
                .requestFactory(clientHttpRequestFactory);
    }

    @Bean
    @Primary
    public OpenAiApi openAiApi(RestClient.Builder restClientBuilder) {
        return OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(WebClient.builder())
                .build();
    }

    @Bean
    @Primary
    public OpenAiChatModel openAiChatModel(OpenAiApi openAiApi) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }
}
