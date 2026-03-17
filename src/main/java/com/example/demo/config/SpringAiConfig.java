package com.example.demo.config;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.*;
import okio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Spring AI HTTP 配置
 * 支持 LLM Provider 切换：openai 或 anthropic
 *
 * 通过 app.llm.provider 配置切换：
 * - openai: 使用 OpenAI API 兼容模型 (如 DeepSeek)
 * - anthropic: 使用 Anthropic API 兼容模型 (如 MiniMax)
 *
 * 注意：
 * 1. OkHttp 用于同步 REST 调用（/api/chat）
 * 2. WebClient 用于 SSE 流式调用（/api/agui）
 * 3. 代理是可选的 - 如果设置了 -Dhttp.proxyHost 和 -Dhttp.proxyPort 则启用
 */
@Configuration
public class SpringAiConfig {

    private static final Logger log = LoggerFactory.getLogger(SpringAiConfig.class);

    @Value("${app.llm.provider:openai}")
    private String provider;

    @Value("${spring.ai.openai.base-url}")
    private String openAiBaseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String openAiApiKey;

    @Value("${spring.ai.openai.chat.options.model}")
    private String openAiModel;

    @Value("${spring.ai.openai.chat.options.temperature}")
    private Double openAiTemperature;

    @Value("${spring.ai.anthropic.base-url}")
    private String anthropicBaseUrl;

    @Value("${spring.ai.anthropic.api-key}")
    private String anthropicApiKey;

    @Value("${spring.ai.anthropic.chat.options.model}")
    private String anthropicModel;

    @Value("${spring.ai.anthropic.chat.options.temperature}")
    private Double anthropicTemperature;

    @Value("${spring.ai.anthropic.chat.options.max-tokens}")
    private Integer anthropicMaxTokens;

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

    /**
     * OpenAI ChatModel Bean
     * 仅在 provider=openai 时创建
     */
    @Bean("openAiChatModel")
    @Primary
    public ChatModel openAiChatModel(RestClient.Builder restClientBuilder) {
        if (!"openai".equals(provider)) {
            log.debug("OpenAI ChatModel skipped, provider is: {}", provider);
            return null;
        }

        log.info("Creating OpenAI ChatModel: baseUrl={}, model={}", openAiBaseUrl, openAiModel);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(openAiBaseUrl)
                .apiKey(openAiApiKey)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(WebClient.builder())
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(openAiModel)
                .temperature(openAiTemperature)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }

    /**
     * Anthropic ChatModel Bean
     * 仅在 provider=anthropic 时创建
     */
    @Bean("anthropicChatModel")
    @Primary
    public ChatModel anthropicChatModel() {
        if (!"anthropic".equals(provider)) {
            log.debug("Anthropic ChatModel skipped, provider is: {}", provider);
            return null;
        }

        log.info("Creating Anthropic ChatModel: baseUrl={}, model={}, maxTokens={}",
                anthropicBaseUrl, anthropicModel, anthropicMaxTokens);

        AnthropicApi anthropicApi = AnthropicApi.builder()
                .baseUrl(anthropicBaseUrl)
                .apiKey(anthropicApiKey)
                .build();

        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(anthropicModel)
                .temperature(anthropicTemperature)
                .maxTokens(anthropicMaxTokens)
                .build();

        return AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(options)
                .build();
    }

    /**
     * 主 ChatModel Bean - 根据 provider 配置动态选择
     * 同时禁用 Spring AI 自动配置的 ChatModel
     */
    @Bean("chatModel")
    @Primary
    @ConditionalOnMissingBean(name = "chatModel")
    public ChatModel chatModel(
            @Autowired(required = false) ChatModel openAiChatModel,
            @Autowired(required = false) ChatModel anthropicChatModel) {
        if ("openai".equals(provider) && openAiChatModel != null) {
            log.info("Using OpenAI ChatModel as primary");
            return openAiChatModel;
        } else if ("anthropic".equals(provider) && anthropicChatModel != null) {
            log.info("Using Anthropic ChatModel as primary");
            return anthropicChatModel;
        }

        // Fallback: 如果没有可用的 ChatModel
        log.warn("No ChatModel available for provider: {}", provider);
        if (openAiChatModel != null) {
            log.warn("Fallback to OpenAI ChatModel");
            return openAiChatModel;
        }
        if (anthropicChatModel != null) {
            log.warn("Fallback to Anthropic ChatModel");
            return anthropicChatModel;
        }
        throw new IllegalStateException("No ChatModel configured. Set app.llm.provider to 'openai' or 'anthropic'.");
    }

    /**
     * ChatClient Bean - 自动选择可用的 ChatModel
     */
    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient chatClient(List<ChatModel> chatModels) {
        List<ChatModel> availableModels = chatModels.stream()
                .filter(model -> model != null)
                .toList();

        if (availableModels.isEmpty()) {
            log.warn("""
                No ChatModel configured. LLM features disabled.
                Configure spring.ai.openai.api-key or spring.ai.anthropic.api-key.
                Set app.llm.provider to 'openai' or 'anthropic'.
                """);
            return ChatClient.create(invocation -> {
                throw new IllegalStateException(
                    "ChatClient not configured. Set app.llm.provider to 'openai' or 'anthropic'.");
            });
        }

        ChatModel chatModel = availableModels.get(0);
        log.info("Creating ChatClient with model: {}", chatModel.getClass().getSimpleName());
        return ChatClient.create(chatModel);
    }
}
