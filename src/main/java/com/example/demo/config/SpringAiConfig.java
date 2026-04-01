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
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.ai.minimax.MiniMaxChatOptions;
import org.springframework.ai.minimax.api.MiniMaxApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    // MiniMax 配置
    @Value("${spring.ai.minimax.base-url:https://api.minimax.chat}")
    private String minimaxBaseUrl;

    @Value("${spring.ai.minimax.api-key}")
    private String minimaxApiKey;

    @Value("${spring.ai.minimax.chat.options.model:abab6.5g-chat}")
    private String minimaxModel;

    @Value("${spring.ai.minimax.chat.options.temperature:0.7}")
    private Double minimaxTemperature;

    @Value("${vision.base-url}")
    private String visionBaseUrl;

    @Value("${vision.api-key}")
    private String visionApiKey;

    @Value("${vision.model}")
    private String visionModel;

    @Value("${transcription.base-url}")
    private String transcriptionBaseUrl;

    @Value("${transcription.api-key}")
    private String transcriptionApiKey;

    @Value("${transcription.model}")
    private String transcriptionModelName;

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
    @ConditionalOnProperty(name = "app.llm.provider", havingValue = "openai")
    public ChatModel openAiChatModel(RestClient.Builder restClientBuilder) {
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
    @ConditionalOnProperty(name = "app.llm.provider", havingValue = "anthropic")
    public ChatModel anthropicChatModel() {
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
     * MiniMax ChatModel Bean
     * 仅在 provider=minimax 时创建
     * 注意：使用 @ConditionalOnProperty 确保只在需要时创建
     */
    @Bean("minimaxChatModel")
    @ConditionalOnProperty(name = "app.llm.provider", havingValue = "minimax")
    public ChatModel minimaxChatModel(RestClient.Builder restClientBuilder) {
        log.info("Creating MiniMax ChatModel: baseUrl={}, model={}", minimaxBaseUrl, minimaxModel);

        MiniMaxApi miniMaxApi = new MiniMaxApi(minimaxBaseUrl, minimaxApiKey, restClientBuilder);

        MiniMaxChatOptions options = MiniMaxChatOptions.builder()
                .model(minimaxModel)
                .temperature(minimaxTemperature)
                .build();

        return new MiniMaxChatModel(miniMaxApi, options);
    }

    /**
     * 主 ChatModel Bean - 根据 provider 配置动态选择
     * 这是唯一被标记为 @Primary 的 ChatModel
     */
    @Bean("chatModel")
    @Primary
    public ChatModel chatModel(
            @Autowired(required = false) @Qualifier("openAiChatModel") ChatModel openAiChatModel,
            @Autowired(required = false) @Qualifier("anthropicChatModel") ChatModel anthropicChatModel,
            @Autowired(required = false) @Qualifier("miniMaxChatModel") ChatModel miniMaxChatModel) {
        
        ChatModel selectedModel = null;
        String modelName = null;
        
        if ("openai".equals(provider)) {
            selectedModel = openAiChatModel;
            modelName = "OpenAI";
        } else if ("anthropic".equals(provider)) {
            selectedModel = anthropicChatModel;
            modelName = "Anthropic";
        } else if ("minimax".equals(provider)) {
            selectedModel = miniMaxChatModel;
            modelName = "MiniMax";
        }
        
        if (selectedModel != null) {
            log.info("Using {} ChatModel as primary (provider={})", modelName, provider);
            return selectedModel;
        }

        // Fallback: 如果没有找到对应 provider 的 ChatModel
        log.error("No ChatModel available for provider: {}", provider);
        throw new IllegalStateException(
            "No ChatModel configured for provider: " + provider + 
            ". Set app.llm.provider to 'openai', 'anthropic', or 'minimax'.");
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

    /**
     * 视觉模型 ChatClient Bean（用于图片理解）
     * 使用火山方舟/豆包视觉 API
     */
    @Bean("visionChatClient")
    public ChatClient visionChatClient() {
        if (visionBaseUrl == null || visionBaseUrl.isBlank() ||
            visionApiKey == null || visionApiKey.isBlank() ||
            visionModel == null || visionModel.isBlank()) {
            log.warn("Vision configuration is incomplete. visionChatClient will not be created.");
            return ChatClient.create(invocation -> {
                throw new IllegalStateException("visionChatClient not configured. Set vision.base-url, vision.api-key, and vision.model.");
            });
        }

        log.info("Creating Vision ChatClient: baseUrl={}, model={}", visionBaseUrl, visionModel);

        OpenAiApi visionApi = OpenAiApi.builder()
                .baseUrl(visionBaseUrl)
                .apiKey(visionApiKey)
                .webClientBuilder(WebClient.builder())
                .build();

        OpenAiChatModel visionChatModel = OpenAiChatModel.builder()
                .openAiApi(visionApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(visionModel)
                        .build())
                .build();

        return ChatClient.create(visionChatModel);
    }

    /**
     * 语音转写模型 Bean（用于语音转文字）
     * 使用智谱 GLM-ASR API（需要 /v4/audio/transcriptions 端点）
     */
    @Bean
    public TranscriptionModel transcriptionModel(RestClient.Builder restClientBuilder) {
        if (transcriptionBaseUrl == null || transcriptionBaseUrl.isBlank() ||
            transcriptionApiKey == null || transcriptionApiKey.isBlank() ||
            transcriptionModelName == null || transcriptionModelName.isBlank()) {
            log.warn("Transcription configuration is incomplete. Creating no-op TranscriptionModel.");
            return new TranscriptionModel() {
                @Override
                public org.springframework.ai.audio.transcription.AudioTranscriptionResponse call(
                        org.springframework.ai.audio.transcription.AudioTranscriptionPrompt prompt) {
                    return new org.springframework.ai.audio.transcription.AudioTranscriptionResponse(
                            new org.springframework.ai.audio.transcription.AudioTranscription("【语音转写功能未配置，无法处理音频】"));
                }
            };
        }

        log.info("Creating Custom TranscriptionModel for GLM-ASR: baseUrl={}, model={}", transcriptionBaseUrl, transcriptionModelName);

        // 智谱 GLM-ASR 使用 /v4/audio/transcriptions 端点
        final String glmAsrEndpoint = transcriptionBaseUrl.endsWith("/")
            ? transcriptionBaseUrl + "v4/audio/transcriptions"
            : transcriptionBaseUrl + "/v4/audio/transcriptions";
        final String apiKey = transcriptionApiKey;
        final String model = transcriptionModelName;

        return new TranscriptionModel() {
            @Override
            public org.springframework.ai.audio.transcription.AudioTranscriptionResponse call(
                    org.springframework.ai.audio.transcription.AudioTranscriptionPrompt prompt) {

                try {
                    // AudioTranscriptionPrompt.getInstructions() 返回 Resource
                    org.springframework.core.io.Resource audioResource = prompt.getInstructions();
                    String originalFilename = "audio.mp3";

                    // 尝试获取原始文件名
                    if (audioResource instanceof org.springframework.core.io.FileSystemResource) {
                        java.io.File file = ((org.springframework.core.io.FileSystemResource) audioResource).getFile();
                        originalFilename = file.getName();
                    }

                    if (audioResource == null) {
                        org.springframework.ai.audio.transcription.AudioTranscription audio =
                            new org.springframework.ai.audio.transcription.AudioTranscription("【语音转写失败】无法获取音频资源");
                        return new org.springframework.ai.audio.transcription.AudioTranscriptionResponse(audio);
                    }

                    // 调用转写方法
                    String result = doTranscribe(audioResource, originalFilename);
                    org.springframework.ai.audio.transcription.AudioTranscription audioTranscription =
                        new org.springframework.ai.audio.transcription.AudioTranscription(result);
                    return new org.springframework.ai.audio.transcription.AudioTranscriptionResponse(audioTranscription);

                } catch (Exception e) {
                    log.error("GLM-ASR 转写失败: {}", e.getMessage(), e);
                    org.springframework.ai.audio.transcription.AudioTranscription audio =
                        new org.springframework.ai.audio.transcription.AudioTranscription("【语音转写失败】" + e.getMessage());
                    return new org.springframework.ai.audio.transcription.AudioTranscriptionResponse(audio);
                }
            }

            @Override
            public String transcribe(org.springframework.core.io.Resource audioResource) {
                // MultimodalAgentService 调用此方法
                try {
                    String originalFilename = "audio.mp3";

                    // 尝试获取原始文件名
                    if (audioResource instanceof org.springframework.core.io.FileSystemResource) {
                        java.io.File file = ((org.springframework.core.io.FileSystemResource) audioResource).getFile();
                        originalFilename = file.getName();
                    }

                    return doTranscribe(audioResource, originalFilename);

                } catch (Exception e) {
                    log.error("GLM-ASR 转写失败: {}", e.getMessage(), e);
                    return "【语音转写失败】" + e.getMessage();
                }
            }

            private String doTranscribe(org.springframework.core.io.Resource audioResource, String filename) {
                try {
                    // 读取音频文件内容
                    byte[] audioBytes;
                    try (java.io.InputStream is = audioResource.getInputStream()) {
                        audioBytes = is.readAllBytes();
                    }

                    // 确保音频为单声道格式（GLM ASR 要求）
                    audioBytes = ensureMonoAudio(audioBytes, filename);

                    // 使用 HttpURLConnection 发送 multipart 请求
                    java.net.URL url = new java.net.URL(glmAsrEndpoint);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);

                    String boundary = "----SpringFormBoundary" + System.currentTimeMillis();
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                    try (java.io.OutputStream os = conn.getOutputStream()) {
                        String partBoundary = "--" + boundary;

                        // 写入 file 部分
                        os.write((partBoundary + "\r\n").getBytes());
                        os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n").getBytes());
                        os.write("Content-Type: audio/mpeg\r\n\r\n".getBytes());
                        os.write(audioBytes);
                        os.write("\r\n".getBytes());

                        // 写入 model 部分
                        os.write((partBoundary + "\r\n").getBytes());
                        os.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n".getBytes());
                        os.write(model.getBytes());
                        os.write("\r\n".getBytes());

                        // 写入 stream 部分
                        os.write((partBoundary + "\r\n").getBytes());
                        os.write("Content-Disposition: form-data; name=\"stream\"\r\n\r\n".getBytes());
                        os.write("false".getBytes());
                        os.write("\r\n".getBytes());

                        // 结束边界
                        os.write((partBoundary + "--\r\n").getBytes());
                    }

                    // 读取响应
                    StringBuilder responseBuilder = new StringBuilder();
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            responseBuilder.append(line);
                        }
                    }

                    // 解析 JSON 响应
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode jsonNode = mapper.readTree(responseBuilder.toString());
                    return jsonNode.has("text") ? jsonNode.get("text").asText() : responseBuilder.toString();

                } catch (Exception e) {
                    log.error("GLM-ASR API 调用失败: {}", e.getMessage(), e);
                    return "【语音转写失败】" + e.getMessage();
                }
            }

            /**
             * 确保音频为单声道格式（GLM ASR 要求），返回处理后的音频字节数组
             */
            private byte[] ensureMonoAudio(byte[] audioBytes, String filename) {
                // 创建临时文件
                java.io.File tempInput = null;
                java.io.File tempOutput = null;
                try {
                    tempInput = java.io.File.createTempFile("glm_asr_input_", getFileExtension(filename));
                    tempInput.deleteOnExit();
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempInput)) {
                        fos.write(audioBytes);
                    }

                    // 检查音频通道数
                    int channels = getAudioChannels(tempInput);
                    log.debug("音频通道数: {}", channels);

                    if (channels <= 1) {
                        log.debug("音频已是单声道，直接使用");
                        return audioBytes;
                    }

                    // 需要转换为单声道
                    log.info("音频为 {} 声道，转换为单声道...", channels);
                    tempOutput = java.io.File.createTempFile("glm_asr_mono_", getFileExtension(filename));
                    tempOutput.deleteOnExit();

                    boolean converted = convertToMono(tempInput, tempOutput);
                    if (converted && tempOutput.exists()) {
                        return java.nio.file.Files.readAllBytes(tempOutput.toPath());
                    }

                    log.warn("音频转换失败，使用原始音频");
                    return audioBytes;

                } catch (Exception e) {
                    log.error("音频格式处理失败: {}", e.getMessage(), e);
                    return audioBytes;
                } finally {
                    // 清理临时文件
                    if (tempInput != null && tempInput.exists()) {
                        tempInput.delete();
                    }
                    if (tempOutput != null && tempOutput.exists()) {
                        tempOutput.delete();
                    }
                }
            }

            private String getFileExtension(String filename) {
                if (filename == null || !filename.contains(".")) {
                    return ".mp3";
                }
                return filename.substring(filename.lastIndexOf("."));
            }

            private int getAudioChannels(java.io.File audioFile) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                        "ffprobe", "-v", "quiet",
                        "-show_entries", "stream=channels",
                        "-of", "csv=p=0",
                        audioFile.getAbsolutePath()
                    );
                    pb.redirectErrorStream(true);
                    Process process = pb.start();

                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(process.getInputStream()))) {
                        String line = reader.readLine();
                        if (line != null && !line.trim().isEmpty()) {
                            return Integer.parseInt(line.trim());
                        }
                    }

                    int exitCode = process.waitFor();
                    if (exitCode != 0) {
                        log.warn("ffprobe 返回非零退出码: {}", exitCode);
                    }
                } catch (Exception e) {
                    log.warn("获取音频通道数失败: {}", e.getMessage());
                }
                return -1; // 无法确定，假设单声道
            }

            private boolean convertToMono(java.io.File input, java.io.File output) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                        "ffmpeg", "-y", "-v", "quiet",
                        "-i", input.getAbsolutePath(),
                        "-ac", "1",
                        "-ar", "16000",
                        output.getAbsolutePath()
                    );
                    pb.redirectErrorStream(true);
                    Process process = pb.start();
                    int exitCode = process.waitFor();

                    if (exitCode == 0 && output.exists()) {
                        log.info("音频已成功转换为单声道");
                        return true;
                    } else {
                        // 读取错误输出
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(process.getErrorStream()))) {
                            StringBuilder error = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                error.append(line).append("\n");
                            }
                            log.warn("ffmpeg 转换失败，退出码: {}, 错误: {}", exitCode, error);
                        }
                    }
                } catch (Exception e) {
                    log.error("ffmpeg 转换失败: {}", e.getMessage(), e);
                }
                return false;
            }
        };
    }
}
