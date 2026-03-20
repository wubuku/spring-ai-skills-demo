# 记忆系统改进调研与建议

**目标**: 分析当前记忆系统缺陷，调研行业实践，给出适合 Demo 展示的改进建议

**日期**: 2026-03-20

---

## 一、当前系统分析

### 1.1 已实现的记忆组件

| 组件 | 类型 | 说明 |
|------|------|------|
| `MessageChatMemoryAdvisor` | 短时记忆 | 基于 H2 数据库，20 条消息窗口 |
| `VectorStoreChatMemoryAdvisor` | 语义记忆 | 从 VectorStore 检索相关历史 |
| `QuestionAnswerAdvisor` | 知识库 RAG | 从知识库检索答案 |

### 1.2 核心缺陷

```
用户: 我喜欢索尼的耳机，上次买了一个 WH-1000XM5
用户: 有什么推荐吗？

AI: (无法关联用户偏好) -> 通用推荐
```

**问题**: 虽然有 `VectorStoreChatMemoryAdvisor`，但它只能检索"相关内容"，无法主动提取和凝炼用户偏好。

---

## 二、记忆系统架构理论

### 2.1 多层记忆架构（类比人类记忆）

```
┌─────────────────────────────────────────────────────┐
│                  Working Memory                      │
│              (当前推理上下文 - Prompt)                │
├─────────────────────────────────────────────────────┤
│                  Session Memory                      │
│           (当前会话 - MessageChatMemory)             │
├───────────────────────┬─────────────────────────────┤
│   Episodic Memory     │      Semantic Memory        │
│   (情景记忆)           │        (语义记忆)            │
│   过去的具体经历        │      凝炼的事实和偏好         │
│   "用户上周买过耳机"    │    "用户偏好索尼、品牌意识强"  │
├───────────────────────┴─────────────────────────────┤
│                  Procedural Memory                   │
│                  (程序记忆 - Skills)                 │
└─────────────────────────────────────────────────────┘
```

### 2.2 关键洞察

- **Session Memory**: 当前会话内的上下文
- **Episodic Memory**: 记录具体事件（买过什么、问过什么）
- **Semantic Memory**: 从历史中提取的模式和偏好（品牌偏好、价格敏感度）

---

## 三、行业实践调研

### 3.1 Mem0 方案

GitHub: `mem0ai/mem0` - 被描述为 "AI 智能体的通用记忆层"

核心特点:
- 自动从对话中提取事实
- 支持向量/图/键值混合存储
- 基于相似度合并冲突记忆
- 按用户 ID 隔离记忆

```python
memory = Memory()
memory.add("用户喜欢索尼耳机", user_id="user_123")
memories = memory.search(query="用户有什么耳机推荐", user_id="user_123")
```

### 3.2 LangMem 方案（LangChain）

来自 LangChain 团队，专注于:
- **User Profile**: 结构化用户信息（姓名、语言、时区）
- **Semantic Memory**: 事实和知识
- **Episodic Memory**: Few-shot 示例

```python
manager = create_memory_manager(
    "anthropic:claude-3-5-sonnet",
    schemas=[UserProfile],  # profiles update in-place
    enable_inserts=False     # 只更新，不新增
)
```

### 3.3 Redis + Spring AI 方案

参考 `foojay.io` 的旅行助手实现:

```kotlin
enum class MemoryType {
    EPISODIC,  // 个人体验和偏好
    SEMANTIC   // 一般知识和事实
}

// LongTermMemoryRecorderAdvisor - 关键组件
// 在助手回复后运行，提取原子事实并存储
```

### 3.4 核心模式总结

| 阶段 | 操作 | 说明 |
|------|------|------|
| **Extract** | LLM 提取事实 | 从对话中识别用户偏好、事实 |
| **Consolidate** | 合并去重 | 相似记忆合并，更新权重 |
| **Retrieve** | 语义检索 | 查询时召回相关记忆 |
| **Inject** | 注入 Prompt | 将记忆注入推理上下文 |

---

## 四、改进建议（适合 Demo 实现）

### 4.1 推荐方案: 用户偏好提取器

**核心思路**: 在每次对话后，异步调用 LLM 提取用户偏好，存入 VectorStore

```
┌──────────────┐     ┌───────────────────┐     ┌──────────────┐
│  User Chat   │ --> │ UserPreference    │ --> │ VectorStore  │
│              │     │ Extractor (LLM)   │     │ (带userId)   │
└──────────────┘     └───────────────────┘     └──────────────┘
                                                      │
                                                      v
┌──────────────┐     ┌───────────────────┐     ┌──────────────┐
│   Prompt     │ <-- │ UserProfile       │ <-- │ Semantic     │
│   Injection  │     │ Advisor           │     │ Search       │
└──────────────┘     └───────────────────┘     └──────────────┘
```

### 4.2 新增文件清单

| 文件 | 说明 |
|------|------|
| `src/main/java/com/example/demo/memory/UserPreferenceExtractor.java` | 偏好提取器 |
| `src/main/java/com/example/demo/memory/UserProfileAdvisor.java` | 偏好注入 Advisor |
| `src/main/java/com/example/demo/memory/UserPreference.java` | 偏好数据结构 |

### 4.3 提取 Prompt 示例

```
你是一个客服对话分析助手。请从以下对话中提取用户偏好和关键信息：

用户: 我想买一个3000元以下的耳机
AI: 推荐索尼 WH-1000XM5...
用户: 索尼不错，还有别的推荐吗？

提取的用户偏好（JSON格式）：
{
  "价格偏好": "3000元以下",
  "品牌偏好": ["索尼"],
  "商品类别": "耳机",
  "说明": "用户对索尼品牌有倾向性"
}
```

### 4.4 注入 Prompt 示例

```
当前用户信息：
- 价格偏好：3000元以下
- 品牌偏好：索尼
- 商品类别：耳机

用户问题：还有什么2000元以内的推荐吗？
```

### 4.5 实现优先级

| 优先级 | 组件 | 工作量 | 效果 |
|--------|------|--------|------|
| P0 | `UserPreferenceExtractor` | 低 | 核心功能 |
| P0 | `UserProfileAdvisor` | 低 | 核心功能 |
| P1 | 偏好去重/合并逻辑 | 中 | 优化精度 |
| P2 | 偏好时效性（权重衰减） | 中 | 进阶功能 |

---

## 五、与其他组件的集成

### 5.1 Advisor 链顺序

```java
this.chatClient = builder
    .defaultAdvisors(
        skillsAdvisor,                              // 1. Skills 工具
        MessageChatMemoryAdvisor.builder(...).build(), // 2. 会话记忆
        vectorStoreChatMemoryAdvisor,                // 3. 语义记忆检索
        userProfileAdvisor,                          // 4. 用户偏好注入 (NEW)
        questionAnswerAdvisor                        // 5. 知识库 RAG
    )
    .defaultTools(skillTools)
    .build();
```

### 5.2 用户标识与向量存储复用

**重要**: 当前系统使用 `conversationId` 而非 `userId`。改进方案需要新增用户标识机制。

#### 5.2.1 方案 A: 新增 userId 字段

在 `ChatMessage` 中新增 `userId` 字段：

```java
// ChatMessage.java
private String userId;  // 用户 ID（可选，默认为 "anonymous"）
```

#### 5.2.2 向量存储复用

不需要新增 VectorStore，复用现有的 `SimpleVectorStore`，通过 `userId` 元数据隔离不同用户的偏好。

```java
// 存储时带 userId
Document.builder()
    .text(preferenceJson)
    .metadata(Map.of(
        "userId", userId,
        "type", "user_preference",
        "category", "brand_preference"
    ))
    .build();

// 查询时过滤（使用 filterExpression）
vectorStore.similaritySearch(SearchRequest.builder()
    .query(searchQuery)
    .filterExpression("userId == '" + userId + "'")
    .build());
```

---

## 六、测试场景

```
场景1: 跨会话偏好记忆
  会话1: 用户说喜欢索尼耳机
  会话2: 用户问推荐 -> 应该知道索尼偏好

场景2: 偏好提取准确性
  输入: "我要3000以下的，索尼或 Bose 都行"
  输出: {"价格": "3000以下", "品牌": ["索尼", "Bose"]}

场景3: 偏好叠加
  会话1: 买过耳机
  会话2: 问音箱
  -> 应该同时知道耳机偏好和当前音箱需求
```

---

## 七、风险与限制

| 风险 | 说明 | 缓解 |
|------|------|------|
| 提取质量 | LLM 提取可能不准确 | 人工审核 prompt |
| 存储膨胀 | 偏好过多时存储增长 | 定期合并/清理 |
| Token 消耗 | 每次提取增加 API 调用 | 异步、非实时 |
| 隐私 | 用户偏好集中存储 | 隔离、加密 |

---

## 八、参考资源

- [Mem0: Universal memory layer for AI Agents](https://github.com/mem0ai/mem0)
- [Agent Memory with Spring AI & Redis - Foojay](https://foojay.io/today/agent-memory-with-spring-ai-redis/)
- [LangMem: Managing User Profiles](https://langchain-ai.github.io/langmem/guides/manage_user_profile/)
- [Spring AI Chat Memory Documentation](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- [DeepLearning.AI: Agent Memory Course](https://learn.deeplearning.ai/courses/agent-memory-building-memory-aware-agents/)
