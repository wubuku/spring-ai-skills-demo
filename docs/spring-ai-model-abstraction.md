# Spring AI 模型适配架构文档

## 概述

本文档阐述 Spring AI 如何通过抽象层适配不同的 LLM Provider（如 OpenAI、Anthropic、MiniMax 等），以及当前项目如何利用这些抽象实现多模型支持。

**注意**：Spring AI 1.x 版本**尚未提供**模型元数据注册表机制（如 context_window、max_output_tokens、reasoning_levels 等配置）。用户期望的 YAML 配置格式是 Spring AI 未来版本可能支持的功能，当前项目通过代码层面的模型特定配置来模拟类似能力。

---

## 1. Spring AI 核心抽象

### 1.1 接口层级

```
ChatModel (接口)
├── OpenAiChatModel (OpenAI / 兼容 OpenAI API 的模型)
├── AnthropicChatModel (Anthropic API)
├── MiniMaxChatModel (MiniMax API)
├── OllamaChatModel (Ollama 本地模型)
└── ... (其他 Provider)

EmbeddingModel (接口)
├── OpenAiEmbeddingModel (OpenAI 兼容嵌入)
└── ...

TranscriptionModel (接口)
└── ... (各 Provider 实现)
```

### 1.2 核心接口

#### ChatModel 接口

```java
public interface ChatModel {
    ChatResponse call(ChatPrompt prompt);
    Flux<ChatResponse> stream(ChatPrompt prompt);
}
```

这是最底层的抽象，定义了 `call()`（同步）和 `stream()`（流式）两个方法。

#### ChatClient

```java
// 基于 ChatModel 的高级 Fluent API
ChatClient.create(chatModel);

// 使用示例
chatClient.prompt()
    .user("Hello")
    .call()
    .content();
```

ChatClient 构建于 ChatModel 之上，提供更友好的链式 API，支持 Advisor 链。

---

## 2. 当前项目的模型配置

### 2.1 配置架构图

```
application.yml
├── spring.ai.openai.*       # OpenAI/DeepSeek 兼容配置
├── spring.ai.anthropic.*    # Anthropic 配置
├── spring.ai.minimax.*     # MiniMax 配置
├── app.llm.provider         # 当前启用的 Provider
└── vision.*                # 视觉模型独立配置

SpringAiConfig.java
├── @ConditionalOnProperty(provider="openai")  → OpenAiChatModel
├── @ConditionalOnProperty(provider="anthropic") → AnthropicChatModel
├── @ConditionalOnProperty(provider="minimax")   → MiniMaxChatModel
└── @Primary ChatModel 动态选择
```

### 2.2 Provider 切换机制

```java
@Bean("openAiChatModel")
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "openai")
public ChatModel openAiChatModel(RestClient.Builder restClientBuilder) {
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
```

通过 `@ConditionalOnProperty` 注解实现**运行时 Bean 条件化注册**。只有 `app.llm.provider` 匹配时，对应的 ChatModel Bean 才会被创建。

### 2.3 多 Provider 条件化创建

```java
@Bean
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

    throw new IllegalStateException(
        "No ChatModel configured for provider: " + provider +
        ". Set app.llm.provider to 'openai', 'anthropic', or 'minimax'.");
}
```

通过 `@ConditionalOnProperty` 注解，每个 Provider 的 ChatModel Bean 只在其对应的 `app.llm.provider` 值匹配时才会被创建。例如，当 `app.llm.provider=openai` 时，只有 `openAiChatModel` Bean 会被创建，`anthropicChatModel` 和 `miniMaxChatModel` 都不会被实例化。

`@Primary chatModel` 方法使用 `@Autowired(required = false)` 注入所有条件化 Bean，然后根据 `provider` 配置的值选择返回哪个。实际实现使用了中间变量 `selectedModel` 和 `modelName` 来记录选择结果，并包含日志记录。

---

## 3. 不同 Provider 的配置差异

### 3.1 OpenAI 兼容模型（DeepSeek 等）

OpenAI 兼容模型使用 `OpenAiChatModel`，通过设置不同的 `baseUrl` 和 `model` 来切换：

```yaml
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com  # 或 https://ark.cn-beijing.volces.com/api/v3
      chat:
        enabled: false
        options:
          model: deepseek-chat  # 切换模型只需改这个
          temperature: 0.7
```

### 3.2 Anthropic 模型

Anthropic 有一些独占参数，如 `maxTokens`：

```java
AnthropicChatOptions options = AnthropicChatOptions.builder()
    .model(anthropicModel)           // 如 claude-3-5-sonnet-20241022
    .temperature(anthropicTemperature)
    .maxTokens(anthropicMaxTokens)   // Anthropic 独占参数
    .build();
```

### 3.3 Vision 模型（特殊处理）

视觉模型使用独立的 ChatClient，通过 `.completionsPath("/chat/completions")` 修复火山方舟 API 路径问题：

```java
@Bean("visionChatClient")
public ChatClient visionChatClient() {
    OpenAiApi visionApi = OpenAiApi.builder()
        .baseUrl(visionBaseUrl)                    // https://ark.cn-beijing.volces.com/api/v3
        .apiKey(visionApiKey)
        .completionsPath("/chat/completions")      // 关键：覆盖默认的 /v1/chat/completions
        .webClientBuilder(WebClient.builder())
        .build();

    OpenAiChatModel visionChatModel = OpenAiChatModel.builder()
        .openAiApi(visionApi)
        .defaultOptions(OpenAiChatOptions.builder()
            .model(visionModel)                     // doubao-1-5-vision-pro-32k-250115
            .build())
        .build();

    return ChatClient.create(visionChatModel);
}
```

### 3.4 MiniMax 模型

MiniMax 有自己的专用 ChatModel 实现 `MiniMaxChatModel`，API 构造方式与其他 Provider 不同：

```java
@Bean("minimaxChatModel")
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "minimax")
public ChatModel minimaxChatModel(RestClient.Builder restClientBuilder) {
    MiniMaxApi miniMaxApi = new MiniMaxApi(minimaxBaseUrl, minimaxApiKey, restClientBuilder);

    MiniMaxChatOptions options = MiniMaxChatOptions.builder()
        .model(minimaxModel)
        .temperature(minimaxTemperature)
        .build();

    return new MiniMaxChatModel(miniMaxApi, options);
}
```

MiniMax 与 OpenAI 的主要区别：
- `MiniMaxApi` 通过构造函数直接实例化，而非 Builder 模式
- 使用 `MiniMaxChatModel` 和 `MiniMaxChatOptions` 而非 OpenAI 的实现类

---

## 4. Spring AI 模型元数据的现状

### 4.1 当前支持的元数据

Spring AI 的 ChatOptions 类支持有限的模型元数据：

| Provider | 支持的元数据 |
|----------|-------------|
| OpenAI | model, temperature, maxTokens, topP, frequencyPenalty, presencePenalty, stop, responseFormat, seed |
| Anthropic | model, temperature, maxTokens, systemPrompt, topP, stopSequences |
| MiniMax | model, temperature, maxTokens, topP |

### 4.2 缺失的功能：模型元数据注册表

用户期望的 YAML 配置格式：

```yaml
app:
  models:
    deepseek-v4-pro:
      context_window: 1000000
      max_output_tokens: 384000
      default_reasoning_level: "high"
      supported_reasoning_levels:
        - effort: "high"
        - effort: "xhigh"
      supports_reasoning_summaries: true
      extensions:
        deepseek_v4:
          enabled: true

  providers:
    deepseek:
      base_url: "https://api.deepseek.com"
      api_key: "sk-xxx"
      offers:
        - model: deepseek-v4-pro
```

**这是 Spring AI 当前版本（1.1.x）尚未支持的功能**。Spring AI 没有提供：
- 模型级别的元数据注册表
- 统一的模型能力描述（context window、reasoning levels 等）
- 模型发现机制

### 4.3 当前项目的变通方案

由于 Spring AI 缺少模型元数据注册表，当前项目采用以下方案：

#### 1. 代码层面的模型特定配置

在 `SpringAiConfig.java` 中，每个模型的默认选项通过对应的 Options 类配置。例如 OpenAI 模型的配置：

```java
OpenAiChatOptions options = OpenAiChatOptions.builder()
    .model(openAiModel)
    .temperature(openAiTemperature)
    .build();
```

如果要针对特定模型进行更精细的配置（例如不同的 context window、max tokens），可以通过在代码中定义模型名称到默认选项的映射来实现。这种方式需要在应用启动时或请求处理前根据模型名称动态选择合适的选项。

#### 2. 视觉提示词模板化

对于需要模型特定处理的场景（如视觉模型的提示词），使用模板文件：

```
prompts/multimodal/
├── vision-prompt.template              # 默认提示词
├── vision-prompt-with-hint.template   # 带用户查询提示
└── vision-prompt-generator.template   # 情境化提示词生成
```

#### 3. Provider 特定的 ChatModel 实现

每个 Provider 有自己的 ChatModel 实现类和 Options 类，无法通过统一接口传递 Provider 特定参数：

```java
// 视觉模型使用 OpenAiChatModel，但配置了不同的 baseUrl 和 path
OpenAiChatModel visionChatModel = OpenAiChatModel.builder()
    .openAiApi(visionApi)
    .defaultOptions(OpenAiChatOptions.builder().model(visionModel).build())
    .build();

// Embedding 使用 OpenAiEmbeddingModel
OpenAiEmbeddingModel embeddingModel = new OpenAiEmbeddingModel(openAiApi, ...);
```

---

## 5. 如何添加新 Provider

以添加 Ollama 为例：

### 5.1 添加依赖

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-ollama</artifactId>
</dependency>
```

### 5.2 创建 Bean

```java
@Bean("ollamaChatModel")
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "ollama")
public ChatModel ollamaChatModel() {
    OllamaApi ollamaApi = OllamaApi.builder()
        .baseUrl(ollamaBaseUrl)  // 如 http://localhost:11434
        .build();

    OllamaChatOptions options = OllamaChatOptions.builder()
        .model(ollamaModel)      // 如 llama3
        .temperature(temperature)
        .build();

    return new OllamaChatModel(ollamaApi, options);
}
```

### 5.3 更新 Primary 选择器

```java
@Bean
@Primary
public ChatModel chatModel(
        @Autowired(required = false) @Qualifier("openAiChatModel") ChatModel openAiChatModel,
        @Autowired(required = false) @Qualifier("anthropicChatModel") ChatModel anthropicChatModel,
        @Autowired(required = false) @Qualifier("miniMaxChatModel") ChatModel miniMaxChatModel,
        @Autowired(required = false) @Qualifier("ollamaChatModel") ChatModel ollamaChatModel) {

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
    } else if ("ollama".equals(provider)) {
        selectedModel = ollamaChatModel;
        modelName = "Ollama";
    }

    if (selectedModel != null) {
        log.info("Using {} ChatModel as primary (provider={})", modelName, provider);
        return selectedModel;
    }

    throw new IllegalStateException(
        "No ChatModel configured for provider: " + provider +
        ". Set app.llm.provider to 'openai', 'anthropic', 'minimax', or 'ollama'.");
}
```

### 5.4 添加配置

```yaml
spring:
  ai:
    ollama:
      base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
      chat:
        enabled: false
        options:
          model: ${OLLAMA_MODEL:llama3}
          temperature: 0.7
```

---

## 6. 架构总结

### 6.1 Spring AI 适配模式

```
┌─────────────────────────────────────────────────────────────┐
│                        Application                          │
│                  (AgentService, etc.)                       │
└─────────────────────────┬───────────────────────────────────┘
                          │ ChatClient.prompt()
                          │
┌─────────────────────────▼───────────────────────────────────┐
│                     ChatClient                              │
│         (Fluent API + Advisor Chain)                       │
└─────────────────────────┬───────────────────────────────────┘
                          │ ChatModel.call()
                          │
┌─────────────────────────▼───────────────────────────────────┐
│                    ChatModel                                │
│              (Interface - 统一抽象)                         │
└────────┬────────────────────┬────────────────────┬───────────┘
         │                    │                    │
┌────────▼────────┐  ┌────────▼────────┐  ┌───────▼────────┐
│ OpenAiChatModel │  │AnthropicChatModel│  │MiniMaxChatModel│
│   (OpenAI API)  │  │  (Anthropic API)  │  │ (MiniMax API)  │
└────────┬────────┘  └────────┬────────┘  └───────┬────────┘
         │                    │                    │
┌────────▼────────┐  ┌────────▼────────┐  ┌───────▼────────┐
│    OpenAiApi    │  │  AnthropicApi   │  │   MiniMaxApi   │
│  (RestClient)   │  │   (RestClient)  │  │  (RestClient)  │
└─────────────────┘  └─────────────────┘  └────────────────┘
```

### 6.2 当前项目组件映射

| 功能 | 使用的 Spring AI 组件 | 配置位置 |
|------|----------------------|---------|
| 文本聊天 | `OpenAiChatModel` / `AnthropicChatModel` / `MiniMaxChatModel` | `SpringAiConfig.java` |
| 流式聊天 | 同上 + `Flux<ChatResponse>` | `StreamingTranscriptionController` |
| 视觉理解 | `OpenAiChatModel` (独立 baseUrl) | `visionChatClient()` |
| Embedding | `OpenAiEmbeddingModel` | `EmbeddingModelConfig.java` |
| 语音转写 | `TranscriptionModel` (自定义实现) | `transcriptionModel()` |
| 向量存储 | `PgVectorStore` / `SimpleVectorStore` | `VectorStoreConfig.java` / `VectorStorePostgresqlConfig.java` |
| 聊天记忆 | `MessageWindowChatMemory` + `JdbcChatMemoryRepository` | `AgentService.java` / `spring.ai.chat.memory` |

---

## 7. 未来展望：Spring AI 模型元数据注册表

用户期望的 YAML 配置格式代表了 Spring AI 未来可能演进的方向。一个理想的模型元数据注册表应该支持：

### 7.1 期望的功能

```yaml
app:
  models:
    deepseek-v4-pro:
      provider: deepseek
      context_window: 1000000
      max_output_tokens: 384000
      capabilities:
        - reasoning
        - function_calling
        - vision

  providers:
    deepseek:
      base_url: https://api.deepseek.com
      api_key: ${DEEPSEEK_API_KEY}
      protocol: openai-chat-completions  # 声明使用 OpenAI Chat Completions API 兼容接口
```

### 7.2 需要的架构变更

1. **ModelMetadata 接口**：统一的模型元数据描述
2. **ModelRegistry**：模型注册表，支持按名称查找
3. **Capability 接口**：描述模型能力（reasoning、vision 等）
4. **Provider 接口**：抽象不同 API 类型（OpenAI 兼容、Anthropic 兼容等）

### 7.3 当前社区讨论

Spring AI 团队正在讨论类似的功能需求，但尚未在正式版本中实现。可以关注 Spring AI 的 GitHub Issues 和 Release Notes 跟进进展。

---

## 8. 用户可选多模型架构

### 8.1 场景需求

**当前架构限制**：
- 使用 `@ConditionalOnProperty` 实现**单模型激活**（启动时决定）
- 只有一个 `@Primary ChatModel` Bean 被注入到服务层
- 无法在运行时根据用户请求切换不同模型

**期望行为**：
- 用户可以在前端**选择**使用 DeepSeek、GLM、MiniMax 等模型
- 每次请求可以使用不同的模型
- 所有模型配置在启动时就加载好（条件化创建可以保留用于未配置的情况）

### 8.2 架构变更方案

#### 核心思路

```
┌─────────────────────────────────────────────────────────────────┐
│                        变更前架构                                │
│  application.yml: app.llm.provider=openai                        │
│                           ↓                                      │
│  @ConditionalOnProperty 只创建 1 个 ChatModel                     │
│                           ↓                                      │
│  @Primary chatModel → AgentService（单一模型）                    │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                        变更后架构                                │
│  application.yml: 配置所有 provider 的 API keys                   │
│                           ↓                                      │
│  所有 ChatModel Beans 都创建（有配置才创建，无配置则跳过）         │
│                           ↓                                      │
│  MultiModelRegistry → AgentService（按需选择模型）                │
│                           ↓                                      │
│  Controller 接收 model 参数 → 路由到对应 ChatModel                  │
└─────────────────────────────────────────────────────────────────┘
```

#### 关键变更 1：移除条件化创建，改为"有配置才创建"

**变更前**：
```java
@Bean("openAiChatModel")
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "openai")  // 条件创建
public ChatModel openAiChatModel(...) {
    // ...
}
```

**变更后**：
```java
@Bean("deepSeekChatModel")
public ChatModel deepSeekChatModel(...) {
    if (deepSeekApiKey == null || deepSeekApiKey.isBlank()) {
        log.info("DeepSeek not configured, skipping...");
        return null;  // 返回 null，由 @Autowired(required=false) 处理
    }
    // 创建 DeepSeek ChatModel
    OpenAiApi api = OpenAiApi.builder()
        .baseUrl(deepSeekBaseUrl)
        .apiKey(deepSeekApiKey)
        .restClientBuilder(restClientBuilder)
        .webClientBuilder(WebClient.builder())
        .build();
    return OpenAiChatModel.builder()
        .openAiApi(api)
        .defaultOptions(OpenAiChatOptions.builder()
            .model(deepSeekModel)
            .build())
        .build();
}
```

#### 关键变更 2：创建 MultiModelRegistry

```java
/**
 * 多模型注册表 - 支持灵活扩展供应商和模型
 *
 * 设计原则：
 * 1. 模型元数据与 ChatModel 分离 - 支持丰富的模型描述（context_window、reasoning_levels 等）
 * 2. 供应商和模型都可以动态注册
 * 3. 每个供应商声明其协议类型（protocol），决定使用哪个 ChatModel 实现
 * 4. 支持日后添加新的协议类型，无需修改核心代码
 */
@Component
public class MultiModelRegistry {

    // ==================== 内部类：模型元数据 ====================

    /**
     * 推理级别定义
     */
    public static class ReasoningLevel {
        private final String effort;       // 如 "high", "xhigh"
        private final String description;  // 友好描述

        public ReasoningLevel(String effort, String description) {
            this.effort = effort;
            this.description = description;
        }

        public String getEffort() { return effort; }
        public String getDescription() { return description; }
    }

    /**
     * 模型元数据 - 包含模型的能力描述（非 ChatModel）
     * 对应外部配置的 models.* 配置块
     */
    public static class ModelMetadata {
        private final String modelId;
        private final int contextWindow;
        private final Integer maxOutputTokens;
        private final String defaultReasoningLevel;
        private final List<ReasoningLevel> supportedReasoningLevels;
        private final boolean supportsReasoningSummaries;
        private final String defaultReasoningSummary;
        private final Map<String, Object> extensions;  // 供应商特定扩展

        public ModelMetadata(String modelId, int contextWindow, Integer maxOutputTokens,
                           String defaultReasoningLevel, List<ReasoningLevel> supportedReasoningLevels,
                           boolean supportsReasoningSummaries, String defaultReasoningSummary,
                           Map<String, Object> extensions) {
            this.modelId = modelId;
            this.contextWindow = contextWindow;
            this.maxOutputTokens = maxOutputTokens;
            this.defaultReasoningLevel = defaultReasoningLevel;
            this.supportedReasoningLevels = supportedReasoningLevels;
            this.supportsReasoningSummaries = supportsReasoningSummaries;
            this.defaultReasoningSummary = defaultReasoningSummary;
            this.extensions = extensions;
        }

        // Getters
        public String getModelId() { return modelId; }
        public int getContextWindow() { return contextWindow; }
        public Integer getMaxOutputTokens() { return maxOutputTokens; }
        public String getDefaultReasoningLevel() { return defaultReasoningLevel; }
        public List<ReasoningLevel> getSupportedReasoningLevels() { return supportedReasoningLevels; }
        public boolean isSupportsReasoningSummaries() { return supportsReasoningSummaries; }
        public String getDefaultReasoningSummary() { return defaultReasoningSummary; }
        public Map<String, Object> getExtensions() { return extensions; }
    }

    /**
     * 模型变体 - 包含元数据 + ChatModel 实例
     * 每个 provider/model-id 组合对应一个 ModelVariant
     */
    public static class ModelVariant {
        private final ModelMetadata metadata;
        private final ChatModel chatModel;  // 用于实际调用

        public ModelVariant(ModelMetadata metadata, ChatModel chatModel) {
            this.metadata = metadata;
            this.chatModel = chatModel;
        }

        public ModelMetadata getMetadata() { return metadata; }
        public ChatModel getChatModel() { return chatModel; }
        public String getModelId() { return metadata.getModelId(); }
    }

    // ==================== 内部类：供应商定义 ====================

    /**
     * 协议类型枚举 - 声明供应商使用的 API 协议标准
     * 使用实际 API 协议名称，便于扩展和维护
     */
    public enum ProtocolType {
        OPENAI_CHAT_COMPLETIONS,  // OpenAI Chat Completions API（也适用于 DeepSeek、GLM 等兼容服务）
        OPENAI_RESPONSES,         // OpenAI Responses API（较新）
        ANTHROPIC_MESSAGES,       // Anthropic Messages API（Anthropic 唯一支持的 API）
        MINIMAX_NATIVE,           // MiniMax 自有协议
        CUSTOM                     // 自定义协议，需要额外实现
    }

    /**
     * 供应商配置 - 对应外部配置的 providers.* 配置块
     */
    public static class ProviderConfig {
        private final String providerId;
        private final String name;
        private final String baseUrl;
        private final String apiKey;
        private final ProtocolType protocolType;
        private final ModelVariant defaultModel;             // 默认模型
        private final Map<String, ModelVariant> modelVariants; // 所有模型变体

        public ProviderConfig(String providerId, String name, String baseUrl, String apiKey,
                            ProtocolType protocolType, ModelVariant defaultModel,
                            Map<String, ModelVariant> modelVariants) {
            this.providerId = providerId;
            this.name = name;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.protocolType = protocolType;
            this.defaultModel = defaultModel;
            this.modelVariants = modelVariants;
        }

        public String getProviderId() { return providerId; }
        public String getName() { return name; }
        public String getBaseUrl() { return baseUrl; }
        public String getApiKey() { return apiKey; }
        public ProtocolType getProtocolType() { return protocolType; }
        public ModelVariant getDefaultModel() { return defaultModel; }

        /**
         * 获取模型变体
         * @param modelId 模型 ID（如 "deepseek-v4-pro"，可为空）
         * @return 对应的 ModelVariant
         */
        public ModelVariant getModelVariant(String modelId) {
            if (modelId == null || modelId.isBlank()) {
                return defaultModel;
            }
            return modelVariants.getOrDefault(modelId, defaultModel);
        }

        public List<String> getModelIds() {
            return new ArrayList<>(modelVariants.keySet());
        }
    }

    // ==================== 注册表核心 ====================

    private final Map<String, ProviderConfig> providers = new ConcurrentHashMap<>();

    @Autowired
    public MultiModelRegistry(
            @Qualifier("deepSeekChatModel") ChatModel deepSeekChatModel,
            @Qualifier("anthropicChatModel") ChatModel anthropicChatModel,
            @Qualifier("minimaxChatModel") ChatModel minimaxChatModel,
            @Qualifier("glmChatModel") ChatModel glmChatModel) {

        // 注册 DeepSeek（OpenAI 兼容协议）
        if (deepSeekChatModel != null) {
            // 模型元数据 - 对应配置中的 models.deepseek-v4-pro
            ModelMetadata deepseekV4ProMeta = new ModelMetadata(
                "deepseek-v4-pro",
                1000000,        // context_window
                384000,         // max_output_tokens
                "high",          // default_reasoning_level
                List.of(
                    new ReasoningLevel("high", "High reasoning effort"),
                    new ReasoningLevel("xhigh", "Extra high reasoning effort")
                ),
                true,            // supports_reasoning_summaries
                "auto",          // default_reasoning_summary
                Map.of("deepseek_v4", Map.of("enabled", true))  // extensions
            );

            Map<String, ModelVariant> deepseekModels = new HashMap<>();
            deepseekModels.put("deepseek-v4-pro",
                new ModelVariant(deepseekV4ProMeta, deepSeekChatModel));

            // 供应商配置 - 对应配置中的 providers.deepseek
            providers.put("deepseek", new ProviderConfig(
                "deepseek",
                "DeepSeek",
                "https://api.deepseek.com",
                "${DEEPSEEK_API_KEY}",
                ProtocolType.OPENAI_CHAT_COMPLETIONS,
                deepseekModels.get("deepseek-v4-pro"),  // 默认模型
                deepseekModels
            ));
        }

        // 注册 Anthropic（原生协议）
        if (anthropicChatModel != null) {
            ModelMetadata claudeMeta = new ModelMetadata(
                "claude",
                200000,         // Claude 3.5 context window
                8192,           // max_output_tokens
                null,           // 无 reasoning levels
                List.of(),
                false,
                null,
                Map.of()
            );

            Map<String, ModelVariant> anthropicModels = new HashMap<>();
            anthropicModels.put("claude", new ModelVariant(claudeMeta, anthropicChatModel));

            providers.put("anthropic", new ProviderConfig(
                "anthropic",
                "Anthropic (Claude)",
                "https://api.anthropic.com",
                "${ANTHROPIC_API_KEY}",
                ProtocolType.ANTHROPIC_MESSAGES,
                anthropicModels.get("claude"),
                anthropicModels
            ));
        }

        // 注册 MiniMax、GLM 等...类似结构

        log.info("MultiModelRegistry initialized: {} providers, protocols: {}",
                providers.size(),
                providers.values().stream()
                    .map(p -> p.getProtocolType().name())
                    .collect(Collectors.joining(", ")));
    }

    // ==================== 公开 API ====================

    /**
     * 获取模型
     * @param modelKey 支持两种格式：
     *                 - "deepseek"              → 返回该 Provider 的默认模型
     *                 - "deepseek/deepseek-v4-pro" → 返回该 Provider 下的指定模型
     */
    public ChatModel getModel(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            throw new IllegalArgumentException("modelKey cannot be null or blank");
        }

        String[] parts = modelKey.split("/", 2);
        String providerId = parts[0];
        String modelId = parts.length > 1 ? parts[1] : null;

        ProviderConfig provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException(
                "Provider not available: " + providerId +
                ". Available providers: " + providers.keySet());
        }

        return provider.getModelVariant(modelId).getChatModel();
    }

    /**
     * 获取模型元数据（用于前端展示）
     */
    public ModelMetadata getModelMetadata(String modelKey) {
        String[] parts = modelKey.split("/", 2);
        String providerId = parts[0];
        String modelId = parts.length > 1 ? parts[1] : null;

        ProviderConfig provider = providers.get(providerId);
        if (provider == null) {
            return null;
        }
        return provider.getModelVariant(modelId).getMetadata();
    }

    public List<String> getAvailableProviders() {
        return new ArrayList<>(providers.keySet());
    }

    public ProviderConfig getProvider(String providerId) {
        return providers.get(providerId);
    }

    public String getProviderName(String providerId) {
        ProviderConfig p = providers.get(providerId);
        return p != null ? p.getName() : providerId;
    }

    // ==================== 扩展方法 ====================

    /**
     * 动态注册新的供应商（用于插件化扩展）
     */
    public void registerProvider(ProviderConfig providerConfig) {
        providers.put(providerConfig.getProviderId(), providerConfig);
        log.info("Registered provider: {} ({})",
                providerConfig.getProviderId(), providerConfig.getProtocolType());
    }
}
```

**扩展设计说明**：

| 扩展场景 | 实现方式 |
|---------|---------|
| 添加新供应商 | 在 `providers` 下添加新条目，声明 `offers` 列表 |
| 添加新协议类型 | 在 `ProtocolType` 枚举添加值 |
| 供应商添加新模型 | 在 `providers.*.offers` 添加模型 ID |
| 模型元数据 | 在 `models` 下添加新条目 |
| 供应商特定扩展 | 通过 `models.*.extensions` Map 存储 |

#### 关键变更 3：Service 层支持模型选择

```java
@Service
public class MultiModelAgentService {

    private final MultiModelRegistry modelRegistry;
    private final Map<String, ChatClient> chatClients = new ConcurrentHashMap<>();

    @Autowired
    public MultiModelAgentService(MultiModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    private ChatClient getChatClient(String modelId) {
        return chatClients.computeIfAbsent(modelId, id -> {
            ChatModel model = modelRegistry.getModel(id);
            return ChatClient.create(model);
        });
    }

    public String chat(String modelId, String userMessage) {
        ChatClient client = getChatClient(modelId);
        return client.prompt()
                .user(userMessage)
                .call()
                .content();
    }

    public Flux<String> chatStream(String modelId, String userMessage) {
        ChatClient client = getChatClient(modelId);
        return client.prompt()
                .user(userMessage)
                .stream()
                .content();
    }
}
```

#### 关键变更 4：Controller 接收模型选择参数

```java
@RestController
@RequestMapping("/api/chat")
public class MultiModelChatController {

    private final MultiModelAgentService agentService;

    @PostMapping("/chat")
    public Map<String, Object> chat(
            @RequestBody Map<String, String> request) {

        // model 可以来自 request body 或 query parameter
        String model = request.getOrDefault("model", "deepseek");
        String userMessage = request.get("message");
        String response = agentService.chat(model, userMessage);

        return Map.of(
            "model", model,
            "response", response
        );
    }

    @PostMapping("/chat/stream")
    public Flux<ChatResponse> chatStream(
            @RequestParam(defaultValue = "deepseek") String model,
            @RequestBody Map<String, String> request) {

        return agentService.chatStream(model, request.get("message"));
    }
}
```

### 8.3 前端模型选择器

#### React 组件示例

```tsx
// components/ModelSelector.tsx

// 从 API 获取可用模型列表
interface Provider {
  id: string;
  name: string;
  models: { id: string; name: string }[];
}

export function ModelSelector({ value, onChange }) {
  const [providers, setProviders] = useState<Provider[]>([]);
  const [selectedProvider, setSelectedProvider] = useState('deepseek');
  const [selectedModel, setSelectedModel] = useState('');

  // 从后端获取可用模型
  useEffect(() => {
    fetch('/api/models')
      .then(res => res.json())
      .then(data => {
        // data 是 Provider 列表
        setProviders(data);
      });
  }, []);

  // 选择 Provider 时，重置模型选择
  const handleProviderChange = (providerId: string) => {
    setSelectedProvider(providerId);
    setSelectedModel('');  // 清空模型选择，使用默认模型
    onChange(providerId);  // 传递 provider ID
  };

  // 选择具体模型时
  const handleModelChange = (modelId: string) => {
    const modelKey = `${selectedProvider}/${modelId}`;
    setSelectedModel(modelId);
    onChange(modelKey);  // 传递完整 key：provider/modelId
  };

  const currentProvider = providers.find(p => p.id === selectedProvider);

  return (
    <div className="flex gap-2">
      {/* Provider 选择器 */}
      <select
        value={selectedProvider}
        onChange={e => handleProviderChange(e.target.value)}
        className="border rounded px-2 py-1"
      >
        {providers.map(p => (
          <option key={p.id} value={p.id}>{p.name}</option>
        ))}
      </select>

      {/* 模型选择器（可选） */}
      {currentProvider && currentProvider.models.length > 0 && (
        <select
          value={selectedModel}
          onChange={e => handleModelChange(e.target.value)}
          className="border rounded px-2 py-1"
        >
          <option value="">使用默认模型</option>
          {currentProvider.models.map(m => (
            <option key={m.id} value={m.id}>{m.name}</option>
          ))}
        </select>
      )}
    </div>
  );
}

// 使用
function ChatPage() {
  const [selectedModel, setSelectedModel] = useState('deepseek');
  const [message, setMessage] = useState('');

  const sendMessage = async () => {
    const response = await fetch('/api/chat/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message,
        model: selectedModel  // 可能是 "deepseek" 或 "deepseek/deepseek-v4-pro"
      })
    });
    const data = await response.json();
    // 处理响应...
  };

  return (
    <div>
      <ModelSelector value={selectedModel} onChange={setSelectedModel} />
      <textarea
        value={message}
        onChange={e => setMessage(e.target.value)}
        className="border w-full mt-2 p-2"
      />
      <button onClick={sendMessage} className="bg-blue-500 text-white px-4 py-2 mt-2 rounded">
        发送
      </button>
    </div>
  );
}
```

**modelKey 格式说明**：
- `deepseek` → DeepSeek 默认模型
- `deepseek/deepseek-v4-pro` → DeepSeek 下指定模型
- `anthropic` → Claude 默认模型
- `glm/glm-4` → GLM 下指定模型

### 8.4 配置示例

本设计采用**两层配置结构**：模型元数据与供应商连接信息分离，便于扩展和维护。

```yaml
# application.yml

app:
  # ==================== 第1层：模型元数据（app.models.*） ====================
  # 定义每个模型的完整能力描述，与具体实现无关
  models:
    # DeepSeek V4 Pro - 支持推理的高级模型
    deepseek-v4-pro:
      context_window: 1000000
      max_output_tokens: 384000
      default_reasoning_level: "high"
      supported_reasoning_levels:
        - effort: "high"
          description: "High reasoning effort"
        - effort: "xhigh"
          description: "Extra high reasoning effort"
      supports_reasoning_summaries: true
      default_reasoning_summary: "auto"
      extensions:
        deepseek_v4:
          enabled: true

    # DeepSeek V4 Flash - 快速版本
    deepseek-v4-flash:
      context_window: 1000000
      max_output_tokens: 384000
      default_reasoning_level: "high"
      supported_reasoning_levels:
        - effort: "high"
          description: "High reasoning effort"
        - effort: "xhigh"
          description: "Extra high reasoning effort"
      supports_reasoning_summaries: true
      default_reasoning_summary: "auto"
      extensions:
        deepseek_v4:
          enabled: true

    # Claude 3.5 Sonnet - Anthropic 模型
    claude-3-5-sonnet:
      context_window: 200000
      max_output_tokens: 8192
      # Claude 不支持 reasoning_levels，保持为空
      supported_reasoning_levels: []
      supports_reasoning_summaries: false
      extensions: {}

    # GLM-4 - 智谱模型
    glm-4:
      context_window: 128000
      max_output_tokens: 4096
      supported_reasoning_levels: []
      supports_reasoning_summaries: false
      extensions: {}

  # ==================== 第2层：供应商配置（app.providers.*） ====================
  # 定义如何连接到每个供应商的 API
  providers:
    # DeepSeek 供应商
    deepseek:
      name: "DeepSeek"
      base_url: "https://api.deepseek.com"
      api_key: "${DEEPSEEK_API_KEY}"
      protocol: "openai-chat-completions"  # OpenAI Chat Completions API 兼容
      offers:
        - model: deepseek-v4-pro
        - model: deepseek-v4-flash

    # Anthropic 供应商
    anthropic:
      name: "Anthropic (Claude)"
      base_url: "https://api.anthropic.com"
      api_key: "${ANTHROPIC_API_KEY}"
      protocol: "anthropic-messages"  # Anthropic Messages API
      offers:
        - model: claude-3-5-sonnet

    # 智谱供应商
    glm:
      name: "GLM (智谱)"
      base_url: "https://open.bigmodel.cn/api/paas/v4"
      api_key: "${GLM_API_KEY}"
      protocol: "openai-chat-completions"
      offers:
        - model: glm-4

    # MiniMax 供应商（示例）
    minimax:
      name: "MiniMax"
      base_url: "https://api.minimax.chat"
      api_key: "${MINIMAX_API_KEY}"
      protocol: "minimax-native"
      offers:
        - model: abab6.5g-chat
```

**配置结构说明**：

| 配置层 | 作用 | 扩展方式 |
|--------|------|---------|
| `app.models.*` | 定义模型的**能力描述**（context_window、推理级别等） | 在 `app.models` 下添加新条目 |
| `app.providers.*` | 定义供应商的**连接信息**（URL、API Key、协议类型） | 在 `app.providers` 下添加新条目 |
| `app.providers.*.offers` | 声明该供应商**支持哪些模型** | 引用 `app.models` 中的模型 ID |

**协议类型 (`protocol`)**：

| 值 | 说明 | 对应实现 |
|-----|------|---------|
| `openai-chat-completions` | OpenAI Chat Completions API（DeepSeek、GLM 等兼容服务使用） | `OpenAiChatModel` |
| `openai-responses` | OpenAI Responses API（较新） | `OpenAiChatModel` |
| `anthropic-messages` | Anthropic Messages API | `AnthropicChatModel` |
| `minimax-native` | MiniMax 自有协议 | `MiniMaxChatModel` |
| `custom` | 自定义协议 | 需要额外实现 |

### 8.5 注意事项

#### 1. Provider 特定参数问题

不同 Provider 有不同的独占参数：

| Provider | 独占参数 | 处理方式 |
|----------|---------|---------|
| Anthropic | `maxTokens` | 在创建 `AnthropicChatModel` 时设置 |
| OpenAI | `responseFormat`, `seed` | 在创建 `OpenAiChatModel` 时设置 |
| MiniMax | - | 使用通用参数 |

**解决方案**：每个模型的默认选项在创建 ChatModel Bean 时设置。如果需要在请求时动态调整，最简单的方式是预先创建多个 ChatModel Beans，每个有不同的默认选项，然后根据需求选择使用。

**注意**：Spring AI 的 `ChatClient` 对请求级别选项覆盖的支持有限。不同 Provider 的选项类不同（`OpenAiChatOptions`、`AnthropicChatOptions` 等），无法通过统一接口传递 Provider 特定参数。建议在创建 ChatModel Bean 时就确定各模型的默认选项。

#### 2. 聊天记忆与会话隔离

如果每个模型维护独立的聊天记忆，需要：

```java
// 按模型隔离 ChatMemory
Map<String, ChatMemory> memories = new ConcurrentHashMap<>();

private ChatMemory getMemory(String modelId, JdbcChatMemoryRepository repo) {
    return memories.computeIfAbsent(modelId, id ->
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(repo)
            .maxMessages(20)
            .build()
    );
}
```

#### 3. 错误处理

```java
@RestController
@RequestMapping("/api/models")
public static class ModelController {

    private final MultiModelRegistry modelRegistry;

    public ModelController(MultiModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    /**
     * 返回可用模型列表（包含完整元数据）
     */
    @GetMapping
    public List<Map<String, Object>> listModels() {
        return modelRegistry.getAvailableProviders().stream()
            .map(providerId -> {
                var provider = modelRegistry.getProvider(providerId);
                return Map.<String, Object>of(
                    "id", providerId,
                    "name", provider.getName(),
                    "protocol", provider.getProtocolType().name(),
                    "models", provider.getModelIds().stream()
                        .map(modelId -> {
                            var variant = provider.getModelVariant(modelId);
                            var meta = variant.getMetadata();
                            return Map.<String, Object>of(
                                "id", modelId,
                                "name", meta.getModelId(),
                                "context_window", meta.getContextWindow(),
                                "max_output_tokens", meta.getMaxOutputTokens() != null ? meta.getMaxOutputTokens() : "",
                                "reasoning_levels", meta.getSupportedReasoningLevels().stream()
                                    .map(r -> Map.of("effort", r.getEffort(), "description", r.getDescription()))
                                    .toList(),
                                "supports_reasoning_summaries", meta.isSupportsReasoningSummaries()
                            );
                        })
                        .toList()
                );
            })
            .toList();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Map<String, String> handleModelNotFound(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
```

### 8.6 渐进式迁移路径

如果现有系统需要平滑迁移到多模型架构：

1. **Phase 1**: 创建 `MultiModelRegistry`，保持现有 `@Primary` 逻辑
2. **Phase 2**: 将 AgentService 改为接受可选的 `modelId` 参数
3. **Phase 3**: 添加模型选择器 UI
4. **Phase 4**: 移除 `@ConditionalOnProperty`，启用全量模型创建

---

## 9. 附录：关键代码文件

| 文件 | 职责 |
|------|------|
| `config/SpringAiConfig.java` | 所有 ChatModel Bean 的定义和选择逻辑 |
| `controller/ChatController.java` | 文本聊天端点 |
| `controller/MultimodalChatController.java` | 多模态聊天端点 |
| `controller/StreamingTranscriptionController.java` | 流式转写端点 |
| `service/AgentService.java` | 核心聊天逻辑（包含 Advisor 链） |
| `service/MultiModelAgentService.java` | 多模型聊天服务（新增） |
| `service/MultiModelRegistry.java` | 模型注册表（新增） |
| `service/MultimodalAgentService.java` | 图片/音频处理 |
| `service/ConversationHistoryService.java` | 会话历史查询 |
| `service/OpenAiStreamingTranscriptionService.java` | OkHttp 流式 ASR |
