# VectorStoreChatMemoryAdvisor 使用示例规划文档

**文档版本**: v1.0
**创建日期**: 2026-03-19
**目标**: 在当前 demo 项目中添加 VectorStoreChatMemoryAdvisor 的使用示例

---

## 一、背景与目标

### 1.1 现有架构

当前 demo 使用的是 `MessageWindowChatMemory` + `JdbcChatMemoryRepository` (H2 数据库) 来存储对话历史。这种方式的局限性：

- **基于精确匹配**：只能通过 conversationId 查找完整对话历史
- **无法语义搜索**：无法找到语义相似但关键词不同的历史对话
- **上下文窗口限制**：即使有 20 条消息限制，也无法智能选择最相关的历史

### 1.2 目标

引入 `VectorStoreChatMemoryAdvisor`，实现基于向量存储的语义记忆搜索：

- 通过相似度搜索找到语义相关的历史对话
- 将相关历史追加到系统提示中
- 使用轻量级文件型向量数据库（SimpleVectorStore）

---

## 二、技术方案

### 2.1 向量数据库选型

**选择**: `SimpleVectorStore` (内存向量存储，支持文件持久化)

**优点**:
- 零依赖，无需外部服务
- 支持将向量状态保存到文件，重启后可加载
- 适合演示和开发环境
- Spring AI 内置支持

**替代方案（未来可升级）**:
- Chroma (需要 Docker)
- PGVector (需要 PostgreSQL)
- Milvus (需要外部服务)

### 2.2 嵌入模型配置

使用 SiliconFlow API 提供嵌入模型（通过 OpenAI 兼容格式）：

```bash
# .env 文件配置
SILICONFLOW_API_KEY=your-siliconflow-api-key
SILICONFLOW_MODEL=BAAI/bge-m3
SILICONFLOW_DIMENSIONS=1024
SILICONFLOW_URL=https://api.siliconflow.cn/v1/embeddings
```

### 2.3 核心组件概览

#### 2.3.1 EmbeddingModel Bean

使用 `OpenAiEmbeddingModel` 配合 SiliconFlow API：

```java
OpenAiEmbeddingModel.builder()
    .openAiApi(OpenAiApi.builder()
        .baseUrl(siliconFlowBaseUrl)
        .apiKey(siliconFlowApiKey)
        .build())
    .embeddingModel(siliconFlowModel)
    .dimensions(siliconFlowDimensions)
    .build()
```

#### 2.3.2 VectorStore Bean

使用 `SimpleVectorStore`（内存向量存储，支持文件持久化）：

```java
SimpleVectorStore.builder(embeddingModel).build()
```

#### 2.3.3 VectorStoreChatMemoryAdvisor 配置

```java
VectorStoreChatMemoryAdvisor.builder(vectorStore)
    .conversationId("default")  // 默认 conversationId
    .build()
```

详细配置见"实现步骤"章节。

---

## 三、实现步骤

### 步骤 1: 添加 Maven 依赖

在 `pom.xml` 中添加：

```xml
<!-- Spring AI Vector Store (包含 SimpleVectorStore) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-vector-store</artifactId>
</dependency>
```

**说明**:
- `spring-ai-vector-store` 模块包含 `SimpleVectorStore` 实现
- 不需要单独的 "simple" starter
- SiliconFlow API 兼容 OpenAI 嵌入 API 格式，项目中已有的 `spring-ai-starter-model-openai` 已足够支持

### 步骤 2: 配置环境变量

在 `.env` 文件中添加 SiliconFlow 嵌入模型配置：

```bash
# 嵌入模型配置（SiliconFlow API，兼容 OpenAI 嵌入格式）
SILICONFLOW_API_KEY=your-siliconflow-api-key
SILICONFLOW_MODEL=BAAI/bge-m3
SILICONFLOW_DIMENSIONS=1024
SILICONFLOW_URL=https://api.siliconflow.cn/v1/embeddings
```

**说明**：SiliconFlow 嵌入模型使用 OpenAI 兼容 API 格式，只需提供 base-url、api-key 和 model 参数即可。

### 步骤 3: 创建配置类

创建新文件 `src/main/java/com/example/demo/config/VectorStoreConfig.java`：

```java
package com.example.demo.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;

import java.io.File;

@Configuration
public class VectorStoreConfig {

    @Value("${siliconflow.api-key:}")
    private String siliconFlowApiKey;

    @Value("${siliconflow.base-url:https://api.siliconflow.cn/v1}")
    private String siliconFlowBaseUrl;

    @Value("${siliconflow.model:BAAI/bge-m3}")
    private String siliconFlowModel;

    @Value("${siliconflow.dimensions:1024}")
    private int siliconFlowDimensions;

    /**
     * 创建嵌入模型 Bean
     * 使用 SiliconFlow API（OpenAI 兼容格式）
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
            .openAiApi(OpenAiApi.builder()
                .baseUrl(siliconFlowBaseUrl)
                .apiKey(siliconFlowApiKey)
                .build())
            .embeddingModel(siliconFlowModel)
            .dimensions(siliconFlowDimensions)
            .build();
    }

    /**
     * 创建 VectorStore Bean (SimpleVectorStore)
     * 支持文件持久化
     */
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(embeddingModel)
            .build();

        // 尝试从文件加载已存储的向量
        File vectorStoreFile = new File("./data/vector-store.json");
        if (vectorStoreFile.exists()) {
            try {
                simpleVectorStore.load(new FileSystemResource(vectorStoreFile));
            } catch (Exception e) {
                // 忽略加载错误，使用空存储
            }
        }

        return simpleVectorStore;
    }

    /**
     * VectorStore 持久化控制器
     * 用于在应用关闭时保存向量数据
     */
    @Bean
    public VectorStorePersistenceExecutor vectorStorePersistenceExecutor(VectorStore vectorStore) {
        return new VectorStorePersistenceExecutor(vectorStore);
    }
}
```

### 步骤 4: 创建持久化执行器

创建新文件 `src/main/java/com/example/demo/config/VectorStorePersistenceExecutor.java`：

```java
package com.example.demo.config;

import jakarta.annotation.PreDestroy;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;

import java.io.File;

/**
 * VectorStore 持久化执行器
 * 在应用关闭时自动保存向量数据到文件
 */
public class VectorStorePersistenceExecutor {

    private static final Logger log = LoggerFactory.getLogger(VectorStorePersistenceExecutor.class);
    private static final String VECTOR_STORE_FILE = "./data/vector-store.json";

    private final VectorStore vectorStore;

    public VectorStorePersistenceExecutor(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @PreDestroy
    public void saveVectorStore() {
        if (vectorStore instanceof SimpleVectorStore) {
            try {
                File file = new File(VECTOR_STORE_FILE);
                file.getParentFile().mkdirs();
                ((SimpleVectorStore) vectorStore).save(new FileSystemResource(file));
                log.info("VectorStore 已保存到: {}", VECTOR_STORE_FILE);
            } catch (Exception e) {
                log.error("保存 VectorStore 失败: {}", e.getMessage());
            }
        }
    }
}
```

### 步骤 5: 修改 AgentService

修改 `src/main/java/com/example/demo/service/AgentService.java`：

**改动点**:
1. 注入 VectorStore 和 EmbeddingModel
2. 创建 VectorStoreChatMemoryAdvisor
3. 将 Advisor 添加到 ChatClient

```java
// 新增导入
// 注意：Spring AI 1.0.0-RC1+ 版本中 VectorStoreChatMemoryAdvisor 在 vectorstore 子包下
import org.springframework.ai.chat.client.advisor.vectorstore.VectorStoreChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.embedding.EmbeddingModel;

@Service
public class AgentService {

    private final ChatClient chatClient;
    private final SkillTools skillTools;
    private final VectorStore vectorStore;  // 新增

    public AgentService(
            ChatClient.Builder builder,
            SkillTools skillTools,
            SkillsAdvisor skillsAdvisor,
            JdbcChatMemoryRepository jdbcChatMemoryRepository,
            VectorStore vectorStore,  // 新增
            EmbeddingModel embeddingModel  // 新增
    ) {
        this.skillTools = skillTools;
        this.vectorStore = vectorStore;

        // 复用现有的会话记忆配置
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(jdbcChatMemoryRepository)
                .maxMessages(20)
                .build();

        // 创建 VectorStoreChatMemoryAdvisor
        VectorStoreChatMemoryAdvisor vectorStoreChatMemoryAdvisor =
            VectorStoreChatMemoryAdvisor.builder(vectorStore)
                .conversationId("default")
                .build();

        this.chatClient = builder
            .defaultAdvisors(
                skillsAdvisor,
                MessageChatMemoryAdvisor.builder(chatMemory).build(),
                vectorStoreChatMemoryAdvisor  // 新增
            )
            .defaultTools(skillTools)
            .build();
    }

    // ... 其他方法保持不变
}
```

### 步骤 6: 修改 AgUiConfig (可选)

如果需要在 AG-UI 协议中也使用 VectorStoreChatMemoryAdvisor：

```java
@Bean
public SpringAIAgent enterpriseAgent(
        @Qualifier("chatModel") ChatModel chatModel,
        SkillTools skillTools,
        SkillsAdvisor skillsAdvisor,
        JdbcChatMemoryRepository jdbcChatMemoryRepository,
        VectorStore vectorStore  // 新增
) throws Exception {

    // 复用现有的会话记忆配置
    ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(jdbcChatMemoryRepository)
            .maxMessages(20)
            .build();

    // 创建 VectorStoreChatMemoryAdvisor
    VectorStoreChatMemoryAdvisor vectorStoreChatMemoryAdvisor =
        VectorStoreChatMemoryAdvisor.builder(vectorStore)
            .build();

    return SpringAIAgent.builder()
            .agentId("enterprise-agent")
            .chatModel(chatModel)
            // ... systemMessage ...
            .tool(skillTools)
            .advisor(skillsAdvisor)
            .advisor(MessageChatMemoryAdvisor.builder(chatMemory).build())
            .advisor(vectorStoreChatMemoryAdvisor)  // 新增
            .build();
}
```

---

## 四、配置属性

### 4.1 新增配置项

在 `src/main/resources/application.yml` 中添加：

```yaml
siliconflow:
  api-key: ${SILICONFLOW_API_KEY:}
  base-url: ${SILICONFLOW_URL:https://api.siliconflow.cn/v1}
  model: ${SILICONFLOW_MODEL:BAAI/bge-m3}
  dimensions: ${SILICONFLOW_DIMENSIONS:1024}
```

---

## 五、VectorStoreChatMemoryAdvisor 工作流程

### 5.1 请求流程

```
用户消息
    │
    ▼
[VectorStoreChatMemoryAdvisor.before()]
    │
    ├─ 1. 将用户消息转换为嵌入向量
    │
    ├─ 2. 在 VectorStore 中执行相似度搜索
    │      - 搜索与当前消息语义相似的历史消息
    │      - 使用 conversationId 过滤（如指定）
    │
    └─ 3. 将检索到的历史消息追加到系统提示
           - 使用 {instructions} 占位符（原有系统提示）
           - 使用 {long_term_memory} 占位符（检索到的历史）
    │
    ▼
[SkillsAdvisor.before()]
    │
    ▼
[LLM 调用]
```

### 5.2 响应流程

```
[LLM 响应]
    │
    ▼
[VectorStoreChatMemoryAdvisor.after()]
    │
    ├─ 1. 将对话消息（用户+助手）写入 VectorStore
    │
    └─ 2. 返回响应
    │
    ▼
返回给用户
```

### 5.3 提示模板

VectorStoreChatMemoryAdvisor 默认使用以下模板：

```
{instructions}

{long_term_memory}
```

- `{instructions}`: 原始系统提示（来自 SkillsAdvisor）
- `{long_term_memory}`: 从 VectorStore 检索到的语义相似历史

---

## 六、文件变更清单

### 6.1 新增文件

| 文件路径 | 描述 |
|---------|------|
| `src/main/java/com/example/demo/config/VectorStoreConfig.java` | 向量存储配置类 |
| `src/main/java/com/example/demo/config/VectorStorePersistenceExecutor.java` | 向量存储持久化执行器 |

### 6.2 修改文件

| 文件路径 | 修改内容 |
|---------|---------|
| `pom.xml` | 添加 spring-ai-vector-store 依赖 |
| `.env` | 添加 SiliconFlow API 配置 |
| `src/main/resources/application.yml` | 添加 siliconflow 配置段 |
| `src/main/java/com/example/demo/service/AgentService.java` | 注入 VectorStoreChatMemoryAdvisor |
| `src/main/java/com/example/demo/config/AgUiConfig.java` | (可选) 为 SpringAIAgent 添加 VectorStoreChatMemoryAdvisor |

---

## 七、风险评估与注意事项

### 7.1 风险点

1. **API 依赖**：依赖 SiliconFlow API 可用性
2. **嵌入成本**：每次请求都需要调用嵌入 API（用户消息和检索结果）
3. **向量存储大小**：SimpleVectorStore 是内存存储，数据量过大可能影响性能

### 7.2 缓解措施

1. **Fallback 机制**：如果 SiliconFlow API 不可用，可回退到本地嵌入模型
2. **数据隔离**：通过 conversationId 过滤，确保只检索相关对话
3. **限制数量**：通过 topK 参数限制检索结果数量

### 7.3 注意事项

1. **重复存储**：同时使用 MessageChatMemoryAdvisor 和 VectorStoreChatMemoryAdvisor 会导致消息被存储两次
   - 建议：根据场景选择其一，或明确区分用途
2. **文件持久化**：需要在应用关闭时保存向量数据
3. **维度匹配**：确保嵌入模型的输出维度与配置一致（BGE-m3 = 1024）

---

## 八、测试计划

### 8.1 单元测试

1. **VectorStoreConfig 测试**：验证 Bean 创建
2. **持久化测试**：验证向量数据可以正确保存和加载

### 8.2 集成测试

1. **对话测试**：连续多轮对话，验证历史检索
2. **语义检索测试**：使用语义相似但措辞不同的问题，验证检索效果
3. **并发测试**：多用户并发对话，确保数据隔离

### 8.3 验证点

- [ ] 对话历史正确存储到 VectorStore
- [ ] 相似问题能检索到相关历史
- [ ] 不同 conversationId 的对话相互隔离
- [ ] 应用重启后向量数据正确加载
- [ ] 与现有 SkillsAdvisor 正确协作

---

## 九、附录

### 9.1 参考资料

- [Spring AI VectorStoreChatMemoryAdvisor 文档](https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/chat/client/advisor/vectorstore/VectorStoreChatMemoryAdvisor.html)
- [Spring AI Advisors 文档](https://docs.spring.io/spring-ai/reference/api/advisors.html)
- [Baeldung: Spring AI Advisors](https://www.baeldung.com/spring-ai-advisors)
- [Baeldung: SimpleVectorStore](https://wesome.org/spring-ai-simple-vector-store)

### 9.2 替代向量数据库配置

如果未来需要升级到生产级向量数据库：

**Chroma (Docker)**:
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-chroma</artifactId>
</dependency>
```

**PGVector (PostgreSQL)**:
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
</dependency>
```
