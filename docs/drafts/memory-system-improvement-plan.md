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

#### 缺陷 1: 记忆被动注入，智能体无主动检索能力

**现状**: `VectorStoreChatMemoryAdvisor` 在每次请求时都会自动检索并注入相关历史。

**问题**: 这是一种"被动"的模式——无论智能体是否真正需要，它都会把检索结果塞进 Prompt。智能体没有"主动决定是否查询记忆"的能力，也无法精确指定需要哪类记忆。

```
用户: 今天天气怎么样？（与记忆无关）
AI: [向量检索] → 塞入无关的旧对话 → 浪费 token，干扰判断
```

**理想**: 智能体应该能够主动决定"我需要查询记忆"，而不是每次都自动注入。

---

#### 缺陷 2: 记忆只有"存"，没有"管理"

**现状**: 对话历史只增不减。

**问题**:
- 记忆无限膨胀，检索质量下降
- 无法修正错误记忆（如用户改口说"我其实不喜欢索尼"）
- 无法标记重要记忆 vs 临时信息
- 没有时效性概念（一周前的偏好可能已过期）

```
用户: 我之前说想要耳机...其实我现在想要音箱了
AI: [新对话存入] → 旧偏好(耳机) + 新偏好(音箱) 仍然共存，无从区分
```

---

#### 缺陷 3: 记忆是"原始文本片段"，而非"结构化知识"

**现状**: VectorStore 存储的是对话文本的 embedding。

**问题**:
- 无法直接推理（如"用户的预算范围是？"）
- 检索结果是一整段对话，而非离散的偏好事实
- 多轮对话中的矛盾偏好无法自动发现

```
向量检索返回: "用户说想要3000以下的耳机...但之前又说 Bose 也可以"
智能体需要自己从这段文本中理解偏好，而非直接获取结构化信息
```

**理想**: 偏好应该抽取为结构化数据：`{"品牌": ["索尼", "Bose"], "价格上限": 3000, "类别": "耳机"}`

---

#### 缺陷 4: 缺乏记忆置信度和优先级

**现状**: 所有记忆权重相同。

**问题**:
- 用户随口一说 vs 认真陈述，权重相同
- 无法区分"试探性询问"和"明确需求"
- 检索结果排序只依赖语义相似度，不考虑记忆的重要性或时效性

---

#### 缺陷 5: 没有记忆一致性维护

**现状**: 新对话独立追加，不与旧记忆协调。

**问题**:
- 新旧记忆矛盾时无法自动解决
- 无法识别"记忆漂移"（用户偏好随时间的缓慢变化）

```
会话1: "我喜欢索尼" → 存储: {品牌: 索尼}
会话2: "索尼不行了，换 Bose 吧" → 存储: {品牌: 索尼} + {品牌: Bose}
[冲突未解决]
```

---

#### 缺陷 6: 元数据利用不足

**现状**: 仅用 `conversationId` 过滤，忽略时间戳、来源等元数据。

**问题**:
- 无法按时间衰减权重（近期的记忆更可信）
- 无法区分"购买记录"和"随意闲聊"的可信度
- 批量历史注入时，无法选择性优先注入某类记忆

---

#### 缺陷 7: 没有"用户画像"机制

**现状**: `conversationId` 只区分会话，不区分用户。

**问题**:
- 同一 conversationId 下可能有多个用户
- 无法建立跨 conversationId 的统一用户画像
- 不同用户的偏好会混淆

---

#### 缺陷 8: 上下文窗口压力

**现状**: 每次都把相关历史全部注入。

**问题**:
- 长对话后历史累积，token 消耗剧增
- 大量历史可能稀释关键信息
- 无法聚焦——智能体需要在庞杂的历史中找到真正相关的

---

#### 总结: 缺失的核心能力

| 能力 | 当前状态 | 理想状态 |
|------|----------|----------|
| 主动检索 | 被动注入 | 智能体按需查询 |
| 记忆管理 | 只增不减 | 可更新、可删除、可加权 |
| 知识结构化 | 原始文本 | 结构化实体（品牌/价格/类别） |
| 冲突解决 | 无 | 自动检测和解决 |
| 记忆置信度 | 平等权重 | 区分重要/次要/临时 |
| 用户画像 | 无 | 跨会话用户偏好 |

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

**重要说明**: Spring AI 只提供了基础的记忆组件（`MessageChatMemoryAdvisor`、`VectorStoreChatMemoryAdvisor`、`QuestionAnswerAdvisor`），用于**对话历史的存储和检索**。但以下能力**没有内置解决方案**，需要自建：

- 用户偏好提取（需要 LLM 调用）
- 记忆合并/去重
- 用户画像管理
- 记忆权重/置信度
- 记忆冲突解决

这正是 Mem0、LangMem 等第三方库存在的价值。Demo 的改进方案基于 Spring AI Advisor API 构建自定义组件。

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
