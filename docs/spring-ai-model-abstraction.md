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
    base_url: "https://api.deepseek.com/anthropic"
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
spring:
  ai:
    models:
      deepseek-v4-pro:
        provider: deepseek
        context-window: 1000000
        max-output-tokens: 384000
        capabilities:
          - reasoning
          - function-calling
          - vision

    providers:
      deepseek:
        base-url: https://api.deepseek.com
        api-key: ${DEEPSEEK_API_KEY}
        type: openai  # 声明使用 OpenAI API 兼容接口
```

### 7.2 需要的架构变更

1. **ModelMetadata 接口**：统一的模型元数据描述
2. **ModelRegistry**：模型注册表，支持按名称查找
3. **Capability 接口**：描述模型能力（reasoning、vision 等）
4. **Provider 接口**：抽象不同 API 类型（OpenAI 兼容、Anthropic 兼容等）

### 7.3 当前社区讨论

Spring AI 团队正在讨论类似的功能需求，但尚未在正式版本中实现。可以关注 Spring AI 的 GitHub Issues 和 Release Notes 跟进进展。

---

## 8. 附录：关键代码文件

| 文件 | 职责 |
|------|------|
| `config/SpringAiConfig.java` | 所有 ChatModel Bean 的定义和选择逻辑 |
| `controller/ChatController.java` | 文本聊天端点 |
| `controller/MultimodalChatController.java` | 多模态聊天端点 |
| `controller/StreamingTranscriptionController.java` | 流式转写端点 |
| `service/AgentService.java` | 核心聊天逻辑（包含 Advisor 链） |
| `service/MultimodalAgentService.java` | 图片/音频处理 |
| `service/ConversationHistoryService.java` | 会话历史查询 |
| `service/OpenAiStreamingTranscriptionService.java` | OkHttp 流式 ASR |
