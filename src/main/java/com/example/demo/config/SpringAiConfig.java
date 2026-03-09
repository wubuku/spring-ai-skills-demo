package com.example.demo.config;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.*;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

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
    public RestClient.Builder restClientBuilder() {
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
                .addInterceptor(chain -> {
                    // 这条日志出现就证明请求走的是 OkHttp
                    System.out.println("[OkHttp] --> " + chain.request().url());
                    Response response = chain.proceed(chain.request());
                    System.out.println("[OkHttp] <-- " + response.code()
                            + " Content-Encoding:" + response.header("Content-Encoding")
                            + " Transfer-Encoding:" + response.header("Transfer-Encoding"));
                    return response;
                })
                .build();

        return RestClient.builder()
                .requestFactory(new org.springframework.http.client.OkHttp3ClientHttpRequestFactory(okHttpClient));
    }

    @Bean
    @Primary
    public OpenAiApi openAiApi(RestClient.Builder restClientBuilder) {
        WebClient.Builder webClientBuilder = WebClient.builder();
        return new OpenAiApi(baseUrl, apiKey, restClientBuilder, webClientBuilder);
    }

    @Bean
    @Primary
    public OpenAiChatModel openAiChatModel(OpenAiApi openAiApi) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        return new OpenAiChatModel(openAiApi, options);
    }
}
