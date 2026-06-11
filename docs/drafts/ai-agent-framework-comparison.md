# JavaClaw 与 AgentScope Java：AI Agent Harness 深度调研报告

> 本报告由 Claude Code 辅助生成，基于对两个项目的源码探索和文档分析。

---

## 一、项目背景

### 1.1 调研对象

| 项目 | JavaClaw | AgentScope Java |
|------|----------|----------------|
| **组织** | 社区（JobRunr 团队） | Alibaba |
| **许可证** | LGPL v3 | Apache 2.0 |
| **Java 版本** | Java 25 | JDK 17+ |
| **定位** | 个人 AI 助手 | 生产级企业 Agent 框架 |
| **官网** | - | https://java.agentscope.io/ |
| **GitHub** | - | github.com/agentscope-ai/agentscope-java |

### 1.2 调研方法

- 源码结构分析
- 核心模块源码阅读
- 文档分析
- 架构图还原

---

## 二、JavaClaw 项目分析

### 2.1 项目概述

JavaClaw 是一个 Java 编写的**个人 AI 助手**，运行在自有设备上，作为 AI 代理的控制平面。项目最初为 JobRunr 的演示程序，现已发展为社区驱动的开源项目。

**核心能力**：
- 多渠道交互（Telegram、Discord、Web 聊天）
- 任务管理（Markdown 文件存储 + JobRunr 后台调度）
- Shell 命令执行与文件操作
- 智能网页抓取
- MCP (Model Context Protocol) 支持
- 动态工具发现（Lucene-based）

### 2.2 技术栈

| 类别 | 技术 |
|------|------|
| **语言** | Java 25 |
| **框架** | Spring Boot 4.0.6, Spring Modulith 2.0.6 |
| **LLM 集成** | Spring AI 2.0.0-M6 |
| **后台任务** | JobRunr 8.6.0 |
| **数据库** | H2 (嵌入式) |
| **前端** | htmx + Bulma |
| **构建工具** | Gradle (version catalog) |

**LLM 提供商支持**：OpenAI GPT、Anthropic Claude、Ollama（本地）、Google Gemini

### 2.3 模块架构

```
JavaClaw/
├── base/               # 核心模块
│   └── src/main/java/ai/javaclaw/
│       ├── agent/          # Agent 接口与实现
│       ├── channels/       # Channel 接口与注册表
│       ├── tasks/          # TaskManager、Task、RecurringTask
│       ├── configuration/  # ConfigurationManager
│       ├── tools/          # TaskTool、CheckListTool、McpTool
│       ├── onboarding/     # LLM 提供商引导
│       ├── files/         # YAML 解析
│       └── mcp/           # MCP 连接
├── app/                # Spring Boot 入口 + Web UI
├── providers/          # LLM 提供商实现
│   ├── anthropic/
│   ├── openai/
│   ├── ollama/
│   └── google/
└── plugins/            # 渠道与工具插件
    ├── telegram/
    ├── discord/
    ├── brave/         # Brave 搜索
    └── playwright/    # 浏览器自动化
```

### 2.4 ReAct 实现

JavaClaw 的 ReAct 循环**并非自研实现**，而是委托给 Spring AI 的 `ToolCallAdvisor`。

#### 2.4.1 核心配置

**文件**：`JavaClawConfiguration.java:93-111`

```java
chatClientBuilder
    .defaultAdvisors(new SimpleLoggerAdvisor())
    .defaultSystem(p -> p.text(agentPrompt).param(...))
    .defaultToolCallbacks(...)    // MCP 工具
    .defaultTools(...)             // 内置工具
    .defaultAdvisors(
        toolCallAdvisor,           // ← Spring AI 的 ReAct 实现
        MessageChatMemoryAdvisor.builder(chatMemory).build()
    );
```

#### 2.4.2 Advisor 链

| Advisor | 职责 |
|---------|------|
| `SimpleLoggerAdvisor` | 日志记录 |
| `ToolCallAdvisor` | ReAct 推理-行动循环（Spring AI） |
| `MessageChatMemoryAdvisor` | 对话历史管理 |

#### 2.4.3 ReAct 循环流程

```
1. Reason  → LLM 生成推理（<reasoning> 标签）
2. Act     → LLM 调用工具
3. Observe → ToolCallAdvisor 执行工具，返回结果
4. Repeat  → 结果反馈给 LLM，继续推理直到无工具调用
```

#### 2.4.4 自定义组件

| 组件 | 位置 | 特点 |
|------|------|------|
| `MessageChatMemoryAdvisor` | `org.springframework.ai.chat.client.advisor` | LinkedHashSet 去重 |
| `DefaultAgent` | `agent/DefaultAgent.java` | 简单封装 ChatClient |

### 2.5 记忆系统

#### 2.5.1 架构

```
Channel → Agent.respondTo(conversationId)
            → ChatClient
            → MessageChatMemoryAdvisor.before()  ← 读取 + 去重
            → LLM
            → MessageChatMemoryAdvisor.after()   ← 写入回复
            → FileSystemChatMemoryRepository (YAML 持久化)
```

#### 2.5.2 MessageChatMemoryAdvisor 去重逻辑

```java
// before() 方法
SequencedSet<Message> allMessages = new LinkedHashSet<>(this.chatMemory.get(conversationId));
allMessages.addAll(instructions);  // LinkedHashSet 自动去重，保持顺序
```

**特点**：
- 使用 `LinkedHashSet` 去重，保持插入顺序
- 确保 SystemMessage 始终在第一位
- 仅持久化 USER/ASSISTANT/SYSTEM 消息

#### 2.5.3 窗口化记忆

```java
// MessageWindowChatMemory.java
private List<Message> window(List<Message> messages) {
    // 取最后 maxMessages 条（默认 20），SystemMessages 保留在开头
}
```

- **Repository**：无限存储
- **`get()`**：返回窗口视图（默认 20 条）
- **非修改性**：多次调用不影响存储

#### 2.5.4 持久化格式

```
{workspace}/conversations/chat-{conversationId}.yaml
```

```yaml
---
createdAt: 2026-03-21T10:00:00Z
updatedAt: 2026-03-21T10:05:30Z
---
- user: |
    问题内容
- assistant: |
    回复内容
```

#### 2.5.5 局限性

| 问题 | 说明 |
|------|------|
| **无语义检索** | 无法回答"上周五的任务是什么" |
| **固定窗口** | 超过 20 条后早期记忆丢失 |
| **无长期记忆** | 无跨会话知识保留 |

### 2.6 工具系统

| 工具 | 功能 |
|------|------|
| `TaskTool` | 任务创建/调度（JobRunr 后台） |
| `CheckListTool` | 多步骤跟踪（pending/in_progress/completed） |
| `FileSystemTools` | 文件读写/编辑 |
| `SmartWebFetchTool` | 智能网页抓取 |
| `SkillsTool` | 加载 workspace/skills/ 下的 SKILL.md |
| `McpTool` | MCP 服务器运行时管理 |

**动态工具发现**：默认启用（`matchIfMissing=true`），使用 `ToolSearchToolCallAdvisor`（Lucene 索引）根据查询动态匹配工具，而非将所有工具预先暴露给 LLM。

### 2.7 多渠道支持

| 渠道 | Conversation ID 格式 |
|------|---------------------|
| Web | `"web"` |
| Telegram | `"telegram-{chatId}"` 或 `"telegram-{chatId}-{threadId}"` |
| Discord | `"discord-{channelId}"` |

**ChannelRegistry** 负责消息路由和事件发布（`ChannelMessageReceivedEvent`）。

### 2.8 文件系统依赖

JavaClaw **强制依赖文件系统**，核心存储机制均基于磁盘文件，无任何替代方案。

| 组件 | 实现类 | 存储路径 | 替代方案 |
|------|--------|----------|----------|
| **Chat Memory** | `FileSystemChatMemoryRepository` | `{workspace}/conversations/chat-{channel}.yaml` | ❌ 无 |
| **Task 持久化** | `FileSystemTaskRepository` | `{workspace}/tasks/yyyy-MM-dd/*.md` | ❌ 无 |
| **配置管理** | `ConfigurationManager` | `application.private.yaml` | ❌ 无 |
| **Skills 加载** | `SkillsTool` | `workspace/skills/SKILL.md` | ❌ 无 |
| **H2 数据库** | JobRunr 内置 | `jdbc:h2:file:./workspace/app` | ❌ 无 |
| **动态工具发现** | `LuceneToolSearcher` | Lucene 索引文件 | ❌ 无 |

**无文件系统时的后果**：

| 功能 | 具体影响 |
|------|----------|
| **对话历史** | 重启后全部丢失，无法跨会话恢复 |
| **任务管理** | 无法创建、调度、追踪任务 |
| **定时任务** | 无法持久化或按 cron 执行 |
| **Skills** | 自定义 SKILL.md 无法加载 |
| **配置变更** | 运行时配置修改无法持久化 |
| **动态工具** | Lucene 索引每次重启重建 |

**结论**：JavaClaw 要求 `agent.workspace` 必须指向可写的磁盘路径，**不支持纯内存运行**。如需无文件系统运行，必须自行实现 `ChatMemoryRepository`、`TaskRepository` 等接口并替换 Spring Bean。

---

## 三、AgentScope Java 项目分析

### 3.1 项目概述

AgentScope Java 是阿里巴巴开发的**面向 Agent 的编程框架**，用于构建基于 LLM 的 Java 应用。提供完整的 ReAct 推理、工具调用、记忆管理、多 Agent 协作能力，以及生产级特性（优雅关闭、可观测性）。

### 3.2 技术栈

| 类别 | 技术 |
|------|------|
| **语言** | Java 17+ |
| **响应式** | Project Reactor (Mono/Flux) |
| **Web 框架** | Spring Boot 4.0.4 / Quarkus 3.30.6 |
| **LLM SDK** | 自研 Formatter 模式（直接集成各厂商 SDK） |
| **序列化** | Jackson 2.21.1 |
| **可观测性** | OpenTelemetry 1.61.0 |
| **协议** | MCP、A2A、Nacos、RocketMQ |

**LLM 提供商支持**：OpenAI、Anthropic Claude、Google Gemini、DashScope（阿里云）、Bailian（阿里通义）

### 3.3 模块架构

```
agentscope-java/
├── agentscope-core/              # 核心框架
│   └── io.agentscope.core/
│       ├── ReActAgent.java           # 主 ReAct 实现
│       ├── agent/                    # Agent 接口层次
│       ├── model/                    # LLM 模型抽象
│       ├── formatter/                # 厂商特定格式化器
│       ├── message/                  # 统一消息类型
│       ├── memory/                   # 记忆接口与实现
│       ├── tool/                     # 工具系统
│       ├── hook/                     # 事件钩子系统
│       ├── pipeline/                 # 多 Agent 管道
│       ├── session/                  # 状态持久化
│       ├── plan/                     # 计划任务管理
│       └── rag/                      # RAG 实现
├── agentscope-extensions/         # 扩展模块
│   ├── agentscope-extensions-a2a/          # A2A 协议
│   ├── agentscope-extensions-mem0/          # Mem0 长期记忆
│   ├── agentscope-extensions-rag-*/         # 多种 RAG 后端
│   ├── agentscope-extensions-scheduler/     # Quartz、XXL-Job
│   ├── agentscope-extensions-session-*/     # MySQL、Redis Session
│   ├── agentscope-spring-boot-starters/    # Spring Boot 集成
│   └── ...
└── agentscope-examples/          # 示例应用
```

### 3.4 Agent 接口层次

```java
// Agent.java - 组合所有能力的核心接口
public interface Agent extends CallableAgent, StreamableAgent, ObservableAgent {
    String getAgentId();
    String getName();
    String getDescription();
    void interrupt();
    void interrupt(Msg msg);
}

// 继承链
Agent → AgentBase → StructuredOutputCapableAgent → ReActAgent
```

| 类 | 职责 |
|----|------|
| `AgentBase` | 基础设施：hooks、订阅、中断、追踪、状态管理 |
| `StructuredOutputCapableAgent` | 结构化输出（generate_response 工具模式） |
| `ReActAgent` | 完整 ReAct 循环实现 |

### 3.5 ReAct 实现（自研）

#### 3.5.1 核心流程

```java
executeIteration(iter)
    │
    ├── reasoning(iter, ignoreMaxIters)  [Phase 1: 推理]
    │     - 流式 LLM 响应
    │     - 累积 ThinkingBlock、ToolUseBlock、TextBlock
    │     - 触发 PreReasoningEvent / PostReasoningEvent
    │     - 检查 HITL stop / gotoReasoning / finish
    │
    ├── hasToolCalls? ──yes──→ acting(iter)  [Phase 2: 行动]
    │         no                   │
    │                              │ 执行工具调用
    │                              │ 触发 PreActingEvent / PostActingEvent
    │                              │
    └── summing() ◄───────────────┘
          (达到最大迭代次数)
```

#### 3.5.2 推理阶段

```java
private Mono<Msg> reasoning(int iter, boolean ignoreMaxIters) {
    if (!ignoreMaxIters && iter >= maxIters) {
        return summarizing();  // 达到上限，生成摘要
    }

    ReasoningContext context = new ReasoningContext(getName());

    return checkInterruptedAsync()
        .then(notifyPreReasoningEvent(memory.getMessages()))
        .flatMapMany(event ->
            model.stream(modelInput, toolkit.getToolSchemas(), options)
                .concatMap(chunk -> checkInterruptedAsync().thenReturn(chunk))
        )
        .doOnNext(chunk -> {
            List<Msg> chunkMsgs = context.processChunk(chunk);
            for (Msg msg : chunkMsgs) {
                notifyReasoningChunk(msg, context).subscribe();
            }
        })
        .then(Mono.defer(() -> Mono.justOrEmpty(context.buildFinalMessage())))
        .flatMap(this::notifyPostReasoning)
        .flatMap(event -> {
            if (isFinished(msg)) {
                return Mono.just(msg);  // 无工具调用 → 完成
            }
            return checkInterruptedAsync().then(acting(iter));  // 继续行动
        });
}
```

#### 3.5.3 行动阶段

```java
private Mono<Msg> acting(int iter) {
    List<ToolUseBlock> pendingToolCalls = extractPendingToolCalls();

    if (pendingToolCalls.isEmpty()) {
        return executeIteration(iter + 1);  // 无待执行工具
    }

    return notifyPreActingHooks(pendingToolCalls)
        .flatMap(this::executeToolCalls)      // Toolkit 执行
        .flatMap(results -> {
            // 处理成功/待定结果
            // 检查 HITL stop 或继续
            return executeIteration(iter + 1);
        });
}
```

#### 3.5.4 结束条件

```java
isFinished() → true 当且仅当消息中无 ToolUseBlock
```

### 3.6 记忆系统

#### 3.6.1 接口设计

```java
public interface Memory extends StateModule {
    void addMessage(Msg message);
    List<Msg> getMessages();
    void deleteMessage(int index);
    void clear();
}
```

#### 3.6.2 短期记忆

| 实现 | 特点 |
|------|------|
| `InMemoryMemory` | 简单内存列表 |
| `AutoContextMemory` | 6 级渐进压缩策略 |

#### 3.6.3 长期记忆

```java
public interface LongTermMemory {
    void record(List<Msg> messages);
    List<Msg> retrieve(Msg query);
}
```

**实现**：
| 实现 | 技术 |
|------|------|
| `Mem0LongTermMemory` | 向量嵌入 + LLM 提取 |
| `ReMeLongTermMemory` | Workspace-based，LLM 驱动提取 |
| `BailianLongTermMemory` | 阿里云服务 + 语义搜索 |

#### 3.6.4 Harness 两层记忆架构

```
Layer 1: Daily Log (memory/YYYY-MM-DD.md)
  - 追加写入原始事实记录
  - MemoryFlushManager 管理

Layer 2: Curated Memory (MEMORY.md)
  - MemoryConsolidator 定期重写
  - 每轮注入到系统提示词

Index: SQLite FTS5 (memory_index.db)
  - 全文搜索索引
  - 每次 flush 后增量重建
```

#### 3.6.5 上下文溢出处理

```java
// CompactionFlow
Threshold hit → ConversationCompactor
  → (可选) flushMemories → daily log + index
  → (可选) offloadMessages → sessions/<id>.log.jsonl
  → LLM distill summary
  → replace Memory + setInputMessages (tail)
```

#### 3.6.6 后台维护（6 小时周期）

1. `expireDailyFiles` - 归档 >90 天文件
2. `consolidateMemory` - 合并日志到 MEMORY.md
3. `pruneOldSessions` - 删除 >180 天 session
4. `reindex` - 重建 FTS5 索引

### 3.7 工具系统

#### 3.7.1 注解定义

```java
public class MyTools {
    @Tool(
        name = "get_weather",
        description = "Get current weather for a city",
        strict = false
    )
    public String getWeather(
        @ToolParam(name = "city") String city,
        @ToolParam(name = "unit") String unit
    ) {
        return "Weather data...";
    }
}
```

#### 3.7.2 工具执行流程

```
Toolkit.callTools(List<ToolUseBlock>, ExecutionConfig, Agent, ToolExecutionContext)
    │
    ├── ToolExecutor 执行工具（并行/串行）
    ├── ToolMethodInvoker 反射调用方法
    ├── ToolResultConverter 转换为 ToolResultBlock
    └── 返回 List<ToolResultBlock>
```

#### 3.7.3 高级特性

| 特性 | 说明 |
|------|------|
| **工具组** | `ToolGroupManager` 动态激活/停用 |
| **预设参数** | preset parameters for tools |
| **子 Agent** | `SubagentsHook` + `subagent/` 工具 |
| **大结果卸载** | `ToolResultEvictionHook` → 文件系统 |
| **MCP 支持** | `McpClientManager` 管理 MCP 客户端 |

### 3.8 Hook 系统

#### 3.8.1 事件类型

| 事件 | 时机 | 可修改 |
|------|------|--------|
| `PreCallEvent` | Agent 调用前 | ✅ |
| `PostCallEvent` | Agent 完成后 | ✅ |
| `PreReasoningEvent` | LLM 调用前 | ✅ |
| `PostReasoningEvent` | LLM 响应后 | ✅ |
| `ReasoningChunkEvent` | 流式 chunk | ❌ |
| `PreActingEvent` | 工具执行前 | ✅ |
| `PostActingEvent` | 工具执行后 | ✅ |
| `ActingChunkEvent` | 工具流式 | ❌ |
| `PreSummaryEvent` | 摘要生成前 | ✅ |
| `PostSummaryEvent` | 摘要生成后 | ✅ |
| `SummaryChunkEvent` | 摘要流式输出 | ❌ |
| `ErrorEvent` | 错误时 | ❌ |

#### 3.8.2 Hook 接口

```java
public interface Hook {
    <T extends HookEvent> Mono<T> onEvent(T event);
    default int priority() { return 100; }  // 越小越先执行
}
```

#### 3.8.3 内置 Hook（按优先级）

| 优先级 | Hook | 职责 |
|--------|------|------|
| 0 | AgentTraceHook | 纯日志 |
| 5 | MemoryFlushHook | 事实落盘 |
| 6 | MemoryMaintenanceHook | 请求合并 |
| 10 | CompactionHook | 上下文压缩（可选） |
| 50 | ToolResultEvictionHook | 大结果卸载 |
| 80 | SubagentsHook | 子 Agent 列表注入 |
| 900 | WorkspaceContextHook | 系统提示词组装 |
| 900 | SessionPersistenceHook | Session 保存 |

### 3.9 多 Agent 协作

#### 3.9.1 MsgHub

```java
try (MsgHub hub = MsgHub.builder()
        .participants(alice, bob)
        .announcement(announcement)
        .build()) {
    hub.enter().block();
    alice.call().block();  // Bob 自动收到消息
    bob.call().block();    // Alice 自动收到消息
}
```

#### 3.9.2 Pipeline 模式

| 模式 | 说明 |
|------|------|
| `SequentialPipeline` | Agent 顺序处理 |
| `FanoutPipeline` | 一对多分发 |

#### 3.9.3 Agent 拓扑

- `ObservableAgent.observe(Msg msg)` - 接收消息不响应
- `AgentBase.resetSubscribers()` - 配置订阅拓扑
- `broadcastToSubscribers()` - Agent 调用后自动广播

### 3.10 Harness 架构

```
User.call(msg, ctx)
  │
  ├── bindRuntimeContext(ctx)  [注入身份、恢复 Memory]
  │
  ├── ReActAgent
  │     ├── Hook Chain (按优先级有序)
  │     ├── ReAct Loop (reasoning + acting)
  │     └── Toolkit
  │
  └── forceCompactAndRetry()    [Context 溢出安全网]
```

**设计原则**：
- "Thin Wrapper, Not a New Reasoning Loop"
- Hook 驱动、正交能力
- 共享对象（RuntimeContext、WorkspaceManager、AbstractFilesystem）是唯一耦合点

### 3.11 生产级特性

| 特性 | 说明 |
|------|------|
| **优雅关闭** | 完整支持 |
| **OpenTelemetry** | 追踪、指标、日志 |
| **Session 持久化** | MySQL / Redis / 文件 |
| **Workspace 管理** | WorkspaceManager + 沙箱 |
| **调度器** | Quartz / XXL-Job |
| **服务发现** | Nacos |
| **A2A 协议** | Agent 间通信 |
| **消息队列** | RocketMQ |

### 3.12 文件系统依赖

AgentScope Java **支持可选的文件系统功能**，可通过配置或禁用开关选择是否依赖文件系统。

#### 3.12.1 Workspace 存储后端

| 实现 | 说明 | 文件系统依赖 |
|------|------|-------------|
| `LocalFilesystem` | 本地磁盘 | ✅ 必需 |
| `RemoteFilesystem` + `InMemoryStore` | 纯内存键值存储 | ❌ 无 |
| `RemoteFilesystem` + Redis 等 | 分布式存储 | ❌ 无 |

#### 3.12.2 禁用文件系统后的功能损失

AgentScope 提供 5 个禁用开关，**精确控制**文件系统相关功能。以下是每个开关的详细影响：

##### 1. `disableFilesystemTools()` — 禁用文件操作工具

禁用 `FilesystemTool`（6 个工具），Agent 将**完全无法进行任何文件操作**：

| 工具名 | 功能 | 禁用后影响 |
|--------|------|------------|
| `read_file` | 读取文件内容（支持分页）| ❌ 无法读取任何文件 |
| `write_file` | 创建新文件 | ❌ 无法写入任何文件 |
| `edit_file` | 字符串替换编辑 | ❌ 无法修改任何文件 |
| `grep_files` | 文本模式搜索 | ❌ 无法在文件中搜索 |
| `glob_files` | glob 模式查找文件 | ❌ 无法发现workspace中的文件 |
| `list_files` | 列出目录内容 | ❌ 无法浏览目录结构 |

**实际影响**：Agent 变成"纯对话"模式，只能通过 LLM 推理和工具调用（如果有其他工具）来完成任务，无法操作工作区文件。

##### 2. `disableMemoryTools()` — 禁用记忆搜索工具

禁用 5 个工具，Agent 将**失去跨会话知识检索能力**：

| 工具名 | 功能 | 禁用后影响 |
|--------|------|------------|
| `memory_search` | 搜索 `MEMORY.md` 和 `memory/*.md` | ❌ 无法检索历史决策/偏好/事实 |
| `memory_get` | 读取指定行范围 | ❌ 无法获取记忆片段上下文 |
| `session_search` | 搜索历史会话记录 | ❌ 无法查找过去对话内容 |
| `session_list` | 列出可用会话 | ❌ 无法列出/切换会话 |
| `session_history` | 获取会话对话历史 | ❌ 无法恢复历史对话 |

**实际影响**：每个 `call()` 都是独立上下文，Agent 无法记住之前的决策、用户偏好或项目状态。"上周五的任务是什么"这类问题无法回答。

##### 3. `disableMemoryHooks()` — 禁用记忆自动持久化

禁用 `MemoryFlushHook` 和 `MemoryMaintenanceHook` 两个 Hook：

**`MemoryFlushHook`（优先级 5）** — 每次 `call()` 后执行：
- 将当前记忆写入 `MEMORY.md`
- 将对话消息追加到 `sessions/<id>.log.jsonl`
- 禁用后：记忆只存在于 `InMemoryMemory`，**重启后全部丢失**

**`MemoryMaintenanceHook`（优先级 6）** — 定期维护（默认 30 分钟间隔）：
- 归档 >90 天的 `memory/YYYY-MM-DD.md` 文件到 `memory/archive/`
- LLM 驱动的记忆合并（`MemoryConsolidator`）
- 删除 >180 天的会话日志
- 禁用后：历史记忆文件无限积累，无法自动清理

**实际影响**：长期记忆完全失效，Agent 只能在当前会话期间记住信息。服务器重启后，所有"学到的"知识消失。

##### 4. `disableSessionPersistence()` — 禁用状态自动保存

禁用 `SessionPersistenceHook`（优先级 900）— 在每次 `call()` 成功后保存 Agent 状态：

- 调用 `agent.saveTo(session, sessionKey)` 自动保存
- 恢复时通过 `RuntimeContext` 加载状态
- 禁用后：状态需要**手动保存**（调用 `HarnessAgent.saveTo()`）

**实际影响**：无法实现"记忆恢复"——每次新 `call()` 都是全新状态。如果需要在多轮对话间保持状态，必须手动管理。

##### 5. `disableWorkspaceContext()` — 禁用工作区上下文注入

这是**影响最大**的禁用开关，会导致系统提示词中丢失大量上下文信息：

**注入内容（禁用后丢失）**：

| 注入内容 | 说明 |
|----------|------|
| **Session Context** | Agent 名称、Session 类型、当前日期、OS 平台、workspace 路径、temp 目录路径 |
| **Domain Knowledge 指南** | 引导 Agent 使用 `knowledge/` 目录作为权威数据源 |
| **Memory Recall 指南** | 引导 Agent 使用 `memory_search` 查找历史信息 |
| **Memory Persistence 指南** | 引导 Agent 更新 `MEMORY.md` 记录重要信息 |
| **AGENTS.md 内容** | Agent 人设和本地规范 |
| **MEMORY.md 内容** | 已有的记忆事实（最多 8000 token） |
| **KNOWLEDGE.md 内容** | 领域知识文档 |

**实际影响**：Agent 不知道自己运行在什么环境、不知道工作区在哪里、不知道应该使用哪些工具来管理记忆。对于需要"记住上次工作进度"或"参考项目规范"的场景，完全无法运作。

##### 功能影响总结

| 禁用方法 | 对话能力 | 记忆能力 | 工具能力 | 上下文感知 |
|----------|----------|----------|----------|------------|
| `disableFilesystemTools()` | ✅ 正常 | ⚠️ 记忆存在但无法搜索 | ❌ 无文件操作 | ✅ 有 |
| `disableMemoryTools()` | ⚠️ 限当前会话 | ❌ 无法检索历史 | ✅ 正常 | ✅ 有 |
| `disableMemoryHooks()` | ⚠️ 限当前进程 | ❌ 记忆不持久化 | ✅ 正常 | ✅ 有 |
| `disableSessionPersistence()` | ⚠️ 需手动保存状态 | ⚠️ 状态需手动管理 | ✅ 正常 | ✅ 有 |
| `disableWorkspaceContext()` | ⚠️ 不知道自己是谁/在哪 | ⚠️ 不知道记忆位置 | ⚠️ 不知道工具指南 | ❌ 完全丢失 |

#### 3.12.3 最小运行配置

完全禁用文件系统后，核心 ReAct 循环仍可正常工作：

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .sysPrompt("You are helpful.")
    .disableFilesystemTools()      // 无文件操作工具
    .disableMemoryTools()          // 无记忆搜索工具
    .disableMemoryHooks()          // 无自动记忆持久化
    .disableSessionPersistence()    // 无状态自动保存
    .disableWorkspaceContext()      // 无工作区上下文注入
    .build();

// 核心 reasoning/acting 循环仍可正常工作
```

#### 3.12.4 可用存储后端

| 存储类型 | 实现 | 说明 |
|----------|------|------|
| **Session** | `JsonSession`（文件）、`InMemorySession`、`RedisSession`、`MySqlSession` | 可选分布式 |
| **Memory** | `InMemoryMemory`、`AutoContextMemory` | 均不直接依赖文件系统 |
| **Workspace** | `LocalFilesystem`、`RemoteFilesystem`（可配内存后端）| 可完全禁用 |

---

## 四、深度对比分析

### 4.0 Agent/Harness 架构深度解析

#### 4.0.1 Harness 设计哲学

AgentScope 的 **Harness 层** 是一个精心设计的"薄封装"，其核心理念体现在 `HarnessAgent.java` 的两个关键方法：

**`bindRuntimeContext()` (HarnessAgent.java:282-306)**：
```java
private void bindRuntimeContext(RuntimeContext ctx) {
    if (ctx == null) {
        this.runtimeContext = null;
        return;
    }
    RuntimeContext effective = ensureSessionDefaults(ctx);
    this.runtimeContext = effective;
    if (userIdRef != null) {
        userIdRef.set(effective.getUserId());
    }
    if (sessionIdRef != null) {
        String sid = effective.getSessionKey() != null
                ? effective.getSessionKey().toIdentifier()
                : effective.getSessionId();
        sessionIdRef.set(sid);
    }
    if (effective.getSession() != null && effective.getSessionKey() != null) {
        try {
            delegate.loadIfExists(effective.getSession(), effective.getSessionKey());
        } catch (Exception e) {
            log.warn("Failed to load session state: {}", e.getMessage());
        }
    }
}
```

**目的**：将 `RuntimeContext`（会话 ID、用户 ID、Session 引用、SessionKey、额外属性、工具执行上下文）绑定到 Agent，供后续 `call()` 使用。这实现了**per-call 身份注入**。

**`forceCompactAndRetry()` (HarnessAgent.java:227-259)**：
```java
private Mono<Msg> forceCompactAndRetry(Memory memory, List<Msg> msgs) {
    List<Msg> allMsgs = memory.getMessages();
    if (allMsgs.isEmpty()) {
        return Mono.error(
                new RuntimeException("Context overflow: memory is empty, cannot compact"));
    }
    RuntimeContext ctx = this.runtimeContext;
    String agentId = delegate.getName();
    String sessionId =
            ctx != null && ctx.getSessionId() != null ? ctx.getSessionId() : "default";

    // 强制触发压缩：设置 threshold=1 使 compactIfNeeded 必定执行
    CompactionConfig forceConfig = CompactionConfig.builder().triggerMessages(1).build();
    MemoryFlushManager fm = new MemoryFlushManager(workspaceManager, delegate.getModel());
    ConversationCompactor compactor = new ConversationCompactor(delegate.getModel(), fm);

    return compactor
            .compactIfNeeded(allMsgs, forceConfig, agentId, sessionId)
            .flatMap(
                    opt -> {
                        if (opt.isPresent()) {
                            memory.clear();
                            for (Msg m : opt.get()) {
                                memory.addMessage(m);
                            }
                            return delegate.call(msgs, coreRuntimeForRecovery());
                        }
                        return Mono.error(
                                new RuntimeException(
                                        "Context overflow: emergency compaction yielded no result"));
                    });
}
```

**目的**：当上下文溢出时，强制执行内存压缩后重试。这是**上下文溢出的安全网**。

#### 4.0.2 与 JavaClaw 的对比

| 维度 | AgentScope Java | JavaClaw |
|------|-----------------|----------|
| **上下文管理** | 完整生命周期管理 | 仅滑动窗口 |
| **溢出处理** | 6 级压缩 + forceCompactAndRetry | 无自动处理 |
| **per-call 身份** | RuntimeContext 注入 | 无 |
| **Session 持久化** | 自动保存/恢复 | 仅 YAML 文件 |
| **Workspace** | WorkspaceManager + AbstractFilesystem | 简单目录 |

**核心差异**：AgentScope 通过 `RuntimeContext` 实现 per-call 身份注入和 Session 恢复，而 JavaClaw 完全不具备这种能力。

#### 4.0.3 RuntimeContext 详解

`RuntimeContext` 是 **per-call 元数据容器**，包含：

```java
// RuntimeContext.java:34-52
private final String sessionId;           // 会话 ID
private final String userId;              // 用户 ID
private final Session session;            // Session 持久化接口
private final SessionKey sessionKey;      // Session 唯一标识
private final ConcurrentMap<String, Object> stringAttributes;           // 字符串键属性
private final ConcurrentMap<Class<?>, ConcurrentMap<String, Object>> typedAttributes;  // 类型化属性
private final ToolExecutionContext toolExecutionContext;  // 工具执行上下文
```

**使用模式**：
1. 用户调用 `agent.call(msg, ctx)` 时传入 `RuntimeContext`
2. `HarnessAgent` 调用 `bindRuntimeContext(ctx)` 注入身份
3. Hooks 和 Tools 可通过 `RuntimeContext` 访问会话信息
4. `SessionPersistenceHook` 使用它来持久化状态

#### 4.0.4 共享对象：唯一耦合点

AgentScope 的设计原则之一是**共享对象是唯一耦合点**。三个核心共享对象：

| 对象 | 职责 | 文件 |
|------|------|------|
| `RuntimeContext` | per-call 身份与属性 | `agent/RuntimeContext.java` |
| `WorkspaceManager` | workspace 文件访问 | `harness/.../workspace/WorkspaceManager.java` |
| `AbstractFilesystem` | 存储后端抽象 | `harness/.../filesystem/AbstractFilesystem.java` |

---

#### 4.0.5 AutoContextMemory：6 级压缩策略

**文件**：`AutoContextMemory.java:66-74, 185-306`

当上下文超过阈值时（`msgThreshold=100` 条消息，或 `tokenRatio=0.75` 即 75% maxToken），按顺序尝试以下策略：

| 策略 | 触发条件 | 压缩方式 |
|------|----------|----------|
| **1. 历史工具调用压缩** | 连续 >6 条工具消息 | LLM 总结，替换为摘要 |
| **2. 大消息卸载（保留最近）** | 单条消息 >5KB | 卸载到文件，保留最近 50 条 |
| **3. 大消息卸载（无保护）** | 同上但无最近保护 | 直接卸载 |
| **4. 历史轮次总结** | User-Assistant 对 | LLM 总结早期对话 |
| **5. 当前轮大消息总结** | 当前轮单条 >5KB | LLM 总结当前轮大消息 |
| **6. 当前轮压缩** | 当前轮工具调用多 | 合并并 LLM 总结所有当前轮 |

**压缩阈值配置**：
```java
// AutoContextConfig.java:36-56
msgThreshold = 100;           // 消息数量阈值
tokenRatio = 0.75;           // token 比例（相对于 maxToken）
maxToken = 128K;             // 最大 token 数
largePayloadThreshold = 5KB;  // 大消息阈值
minConsecutiveToolMessages = 6;  // 连续工具消息阈值
```

##### 4.0.5.1 与 JavaClaw 的对比

| 维度 | AgentScope Java | JavaClaw |
|------|-----------------|----------|
| **上下文溢出处理** | 6 级渐进压缩 | ❌ 无自动处理 |
| **压缩策略** | LLM 总结 + 消息卸载 | 不适用 |
| **长期记忆** | LongTermMemory 接口 | ❌ 不支持 |
| **阈值触发** | 自动检测并压缩 | 固定窗口 20 条 |
| **溢出后恢复** | forceCompactAndRetry | 丢失早期消息 |

**结论**：JavaClaw 在上下文溢出处理方面存在**设计空白**，完全没有对应的能力。AgentScope 的 AutoContextMemory 提供了企业级的完整解决方案。

#### 4.0.6 Hook 事件生命周期

Hook 系统是 AgentScope 扩展能力的核心，通过事件驱动的方式在 ReAct 循环的各个关键点插入自定义逻辑。

**完整事件流**：

```
Agent.call(msg, ctx)
    │
    ├── PreCallEvent              [优先级 0-5]
    │     │
    │     ├── PreReasoningEvent   [优先级 0-5]
    │     │     │
    │     │     ├── reasoning() → model.stream()
    │     │     │
    │     │     └── ReasoningChunkEvent (流式通知)
    │     │           │
    │     │     └── PostReasoningEvent [优先级 0-5]
    │     │           │
    │     │     (若无 tool_calls → summing())
    │     │
    │     ├── PreActingEvent      [优先级 0-5]
    │     │     │
    │     │     ├── acting() → executeToolCalls()
    │     │     │
    │     │     └── ActingChunkEvent (流式通知)
    │     │           │
    │     │     └── PostActingEvent [优先级 0-5]
    │     │
    │     └── (重复 reasoning → acting 直到无 tool_calls)
    │
    ├── PreSummaryEvent           [优先级 0-5]
    │     │
    │     ├── summarizing() → LLM 生成摘要
    │     │
    │     ├── SummaryChunkEvent (流式通知)
    │     │
    │     └── PostSummaryEvent [优先级 0-5]
    │
    ├── PostCallEvent             [优先级 900]
    │
    └── (若 context overflow → forceCompactAndRetry())
```

**优先级机制**：
- 数值越低越先执行
- 相同优先级的 Hook 按注册顺序执行
- `Pre*` 事件可修改输入，`Post*` 事件用于副作用

**JavaClaw 的对比**：JavaClaw 完全不具备 Hook 机制，无法在 ReAct 循环中插入自定义逻辑。Spring AI 的 Advisor 模式虽然提供了类似的能力，但无论是事件类型数量（仅 2 种：`before`/`after`）还是细粒度控制（无优先级、无流式 chunk 观察），都与 AgentScope 的 Hook 系统相差甚远。

| 维度 | AgentScope Java | JavaClaw |
|------|-----------------|----------|
| **事件类型数量** | 12 种 | 2 种（before/after） |
| **优先级控制** | ✅ | ❌ |
| **流式 chunk 观察** | ✅ ReasoningChunkEvent, ActingChunkEvent | ❌ |
| **修改输入能力** | ✅ Pre* 事件可修改 | 有限（只能通过返回值影响） |
| **内置 Hook 数量** | 8 种 | 0（使用 Spring AI Advisor） |

---

#### 4.0.7 Workspace 与文件系统

**AbstractFilesystem 接口** (`AbstractFilesystem.java:44-169`)：
```java
public interface AbstractFilesystem {
    LsResult ls(RuntimeContext rt, String path);
    ReadResult read(RuntimeContext rt, String filePath, int offset, int limit);
    WriteResult write(RuntimeContext rt, String filePath, String content);
    EditResult edit(RuntimeContext rt, String filePath, String oldStr, String newStr, boolean replaceAll);
    GrepResult grep(RuntimeContext rt, String pattern, String path, String glob);
    GlobResult glob(RuntimeContext rt, String pattern, String path);
    // ...
}
```

**LocalFilesystem** 特点：
- `virtualMode`：沙箱模式，阻止路径遍历攻击
- `NamespaceFactory`：路径作用域隔离
- 支持 `ripgrep` (rg) 或 Java fallback

##### 4.0.7.1 与 JavaClaw 的对比

| 维度 | AgentScope Java | JavaClaw |
|------|-----------------|----------|
| **Workspace 抽象** | WorkspaceManager 统一管理 | 直接文件系统路径 |
| **沙箱安全** | `virtualMode` 阻止路径遍历 | 无 |
| **工具执行** | WorkspaceManager + AbstractFilesystem | FileSystemTools |
| **文件操作** | 接口抽象，可扩展 | 内置工具 |
| **搜索能力** | ripgrep + Java fallback | 无内置 grep |

**核心差异**：AgentScope 的 Workspace 是**接口抽象**，可插拔多种存储后端；JavaClaw 的 Workspace 是**直接文件系统访问**，无抽象层。

---

### 4.1 架构哲学

| 维度 | JavaClaw | AgentScope Java |
|------|----------|----------------|
| **核心策略** | 委托式（复用 Spring AI） | 自研式（完整控制） |
| **ReAct 位置** | Spring AI `ToolCallAdvisor` | 自研 `ReActAgent` |
| **分层理念** | 单层，所有功能内聚 | 两层（Harness + Core） |
| **扩展方式** | 插件 + Spring 配置 | Hook + Toolkit |
| **响应式** | Spring MVC（非响应式）| Project Reactor |

### 4.2 ReAct 实现深度对比

#### JavaClaw：委托模式

```
用户消息
    │
    ▼
DefaultAgent.respondTo()
    │
    ▼
ChatClient.prompt().call()
    │
    ▼
ToolCallAdvisor (Spring AI)
    ┌─────────────────────────────────────┐
    │ Loop:                               │
    │   1. LLM 生成 reasoning + tool_call │
    │   2. 执行工具，返回 result           │
    │   3. 继续直到无 tool_call            │
    └─────────────────────────────────────┘
    │
    ▼
返回内容
```

**优点**：
- 代码量少，维护成本低
- Spring AI 升级即可获得改进

**缺点**：
- 无自定义控制能力
- HITL 支持受限
- 难以注入自定义逻辑

#### AgentScope Java：自研模式

```
用户消息
    │
    ▼
HarnessAgent.call()
    │
    ├── bindRuntimeContext(ctx)  [Harness]
    │
    ▼
ReActAgent.executeIteration()
    │
    ├── reasoning()
    │     ▼
    │   model.stream() → 累积 chunks
    │     │
    │   PostReasoningEvent
    │     │
    │   isFinished? ──No──→ acting()
    │   │                      │
    │   │                      ▼
    │   │   Toolkit.executeToolCalls()
    │   │     │
    │   │   PostActingEvent
    │   │     │
    │   └──────┘
    │   │
    │  Yes
    │   ▼
    └── 返回消息
    │
    ├── forceCompactAndRetry()  [Harness]
    │
    ▼
返回内容
```

**优点**：
- 完全可控的 ReAct 逻辑
- 细粒度的 Hook 扩展
- 流式 chunk 可观察
- 上下文溢出自动恢复

**缺点**：
- 代码量大，维护成本高
- 需要跟随 LLM API 变化

### 4.3 记忆系统深度对比

#### 4.3.1 JavaClaw：纯窗口模式

```
┌─────────────────────────────────────────┐
│ Repository (YAML files)                 │
│ messages: [msg1, msg2, ..., msg1000]    │
└─────────────────────────────────────────┘
                │
                │ get(conversationId)
                ▼
┌─────────────────────────────────────────┐
│ window() → 最多 20 条                    │
│ [SystemMsg, msg981, msg982, ..., msg1000]│
└─────────────────────────────────────────┘
```

**局限性**：
- 无法回答语义相关但超出窗口的问题
- 无长期知识积累
- 无上下文压缩能力

#### 4.3.2 AgentScope Java：混合模式

```
┌─────────────────────────────────────────────────────┐
│ Short-term Memory                                   │
│ ┌─────────────────────────────────────────────────┐ │
│ │ AutoContextMemory                                │ │
│ │ - 原始消息列表                                   │ │
│ │ - 6 级压缩策略（当上下文超限时）                  │ │
│ └─────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘
                │ 当超过阈值
                ▼
┌─────────────────────────────────────────────────────┐
│ Long-term Memory (向量 + LLM)                        │
│ ┌─────────────────────────────────────────────────┐ │
│ │ Layer 1: memory/YYYY-MM-DD.md (追加日志)         │ │
│ │ Layer 2: MEMORY.md (LLM 整合)                    │ │
│ │ Index: SQLite FTS5 (全文索引)                    │ │
│ └─────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘
```

**优势**：
- 语义检索支持
- 跨会话知识保留
- 上下文溢出自动压缩
- 后台定期整合

#### 4.3.3 检索能力对比

| 问题类型 | JavaClaw | AgentScope Java |
|----------|----------|----------------|
| "最近 5 条消息是什么" | ✅ | ✅ |
| "上周五我们讨论了什么" | ❌ | ✅ (FTS5) |
| "我之前创建的任务状态" | ❌ | ✅ (长期记忆) |
| "我和 AI 讨论过 X 话题" | ❌ | ✅ (向量相似度) |

### 4.4 工具系统深度对比

#### 4.4.1 工具定义对比

| 维度 | JavaClaw | AgentScope Java |
|------|----------|----------------|
| **注解** | Spring AI `@Tool` | 自研 `@Tool` + `@ToolParam` |
| **参数验证** | 依赖 Spring AI | `@ToolParam` 注解 |
| **结果转换** | Spring AI 自动 | `ToolResultConverter` 可定制 |
| **工具组** | ❌ | ✅ `ToolGroupManager` |
| **预设参数** | ❌ | ✅ |

#### 4.4.2 执行模型对比

**JavaClaw**：由 `ToolCallAdvisor` 自动执行，无并行控制

**AgentScope Java**：
```java
Toolkit.callTools()
    │
    ├── Parallel Execution (默认)
    │     │
    │     ├── Tool A ──┐
    │     ├── Tool B ──┼── results
    │     └── Tool C ──┘
    │
    └── Sequential Execution (可配置)
          │
          ├── Tool A → result A
          ├── Tool B → result B
          └── Tool C → result C
```

#### 4.4.3 高级特性

| 特性 | JavaClaw | AgentScope Java |
|------|----------|----------------|
| **MCP 集成** | ✅ | ✅ |
| **子 Agent** | ❌ | ✅ |
| **大结果卸载** | ❌ | ✅ |
| **工具动态激活** | ❌ (Lucene 发现) | ✅ (ToolGroupManager) |

### 4.5 Hook 系统对比

| 维度 | JavaClaw | AgentScope Java |
|------|----------|----------------|
| **事件类型** | 无自定义 Hook | 12 种事件类型 |
| **优先级控制** | ❌ | ✅ (priority 值) |
| **修改能力** | ❌ | ✅ (Pre* 可修改输入) |
| **流式 chunk 观察** | ❌ | ✅ |
| **内置 Hook** | 无 | 8 种 |

### 4.6 多 Agent 对比

| 维度 | JavaClaw | AgentScope Java |
|------|----------|----------------|
| **消息广播** | ❌ | ✅ MsgHub |
| **Pipeline** | ❌ | ✅ Sequential/Fanout |
| **Agent 拓扑** | ❌ | ✅ observe() / subscribe() |
| **子 Agent** | ❌ | ✅ SubagentsHook |
| **任务分发** | JobRunr | PlanNotebook |

### 4.7 工具系统进阶功能

#### 4.7.1 工具注解与 Schema 生成

**AgentScope Java** 的 `@Tool` 和 `@ToolParam` 注解：

```java
// Tool.java:57-133
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
public @interface Tool {
    String name() default "";           // 工具名（默认方法名）
    String description() default "";     // LLM 描述
    boolean strict() default false;      // 严格 Schema 模式
    Class<? extends ToolResultConverter> converter() default DefaultToolResultConverter.class;
}

// ToolParam.java:59-104
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.ANNOTATION_TYPE})
public @interface ToolParam {
    String name();                       // 必填！参数名
    boolean required() default true;    // 是否必填
    String description() default "";    // 参数描述
}
```

**ToolSchemaGenerator** (`ToolSchemaGenerator.java:49-93`) 使用 `vic-tools/jsonschema` 库从 Java 类型生成 JSON Schema：
- 支持完整类型系统（包括泛型）
- 通过 `$defs` 支持引用定义

#### 4.7.2 工具执行器

**ToolExecutor** (`ToolExecutor.java:166-264`) 的执行流程：

```java
Mono<ToolResultBlock> execute(ToolCallParam param) {
    ToolUseBlock toolCall = param.getToolUseBlock();
    AgentTool tool = toolRegistry.getTool(toolCall.getName());

    // 1. 验证工具存在
    // 2. 检查工具组激活状态
    // 3. Schema 输入验证
    // 4. 合并预设参数（preset parameters）
    return tool.callAsync(executionParam)
            .onErrorResume(ToolSuspendException.class, e -> Mono.just(ToolResultBlock.suspended(...)))
            .onErrorResume(e -> Mono.just(ToolResultBlock.error("Tool execution failed: " + e.getMessage())));
}
```

**批量执行** (`ToolExecutor.java:278-341`)：
- 并行：`Flux.mergeSequential` (默认)
- 串行：`Flux.concat`
- 支持超时、重试、关闭守卫

#### 4.7.3 工具组管理

**ToolGroupManager** (`ToolGroupManager.java:231-245`)：

```java
public boolean isActiveTool(String toolName) {
    Set<String> groups = tools.get(toolName);
    if (groups == null || groups.isEmpty()) {
        return true;  // 无组工具默认激活
    }
    for (String groupName : groups) {
        if (isActiveGroup(groupName)) {
            return true;
        }
    }
    return false;
}
```

- 未分组工具默认激活
- 组内任意一个组激活则工具激活
- 支持动态激活/停用组

#### 4.7.4 MCP 集成

**McpClientManager** (`McpClientManager.java:129-210`) 注册 MCP 工具：

```java
Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper,
        List<String> enableTools, List<String> disableTools,
        String groupName, Map<String, Map<String, Object>> presetParametersMapping) {

    return mcpClientWrapper.initialize()
            .then(Mono.defer(mcpClientWrapper::listTools))
            .flatMapMany(Flux::fromIterable)
            .filter(tool -> shouldRegisterTool(tool.name(), enableTools, disableTools))
            .doOnNext(mcpTool -> {
                McpTool agentTool = new McpTool(
                        mcpTool.name(),
                        mcpTool.description(),
                        McpTool.convertMcpSchemaToParameters(mcpTool.inputSchema(), excludeParams),
                        mcpTool.outputSchema(),
                        mcpClientWrapper,
                        presetParams);
                // 注册到 toolkit
            });
}
```

**McpTool** (`McpTool.java:172-192`) 执行 MCP 调用：
- 合并预设参数与输入参数（输入优先）
- 调用 `clientWrapper.callTool(name, mergedArgs)`

#### 4.7.5 子 Agent

**SubagentsHook** (`SubagentsHook.java:204-246`) 在 `PreReasoningEvent` 时注入子 Agent 提示：

```java
// 构建子 Agent 列表提示
String agentList = entries.stream()
        .map(e -> String.format("- `%s`: %s", e.name(), e.description()))
        .collect(Collectors.joining("\n"));

// 注入到系统提示
event.appendSystemContent(String.format(SUBAGENT_SECTION_TEMPLATE, ...));
```

**SubAgentTool** (`SubAgentTool.java:131-205`) 执行子 Agent：

```java
private Mono<ToolResultBlock> executeConversation(ToolCallParam param) {
    // 获取或创建 session ID
    String sessionId = input.get(PARAM_SESSION_ID);
    boolean isNewSession = sessionId == null;
    if (isNewSession) sessionId = UUID.randomUUID().toString();

    // 创建/获取 Agent
    Agent agent = agentProvider.provide();

    // 加载已有状态（继续会话）
    if (!isNewSession && agent instanceof StateModule) {
        loadAgentState(sessionId, (StateModule) agent);
    }

    // 执行并保存状态
    return result.doOnSuccess(r -> {
        if (agent instanceof StateModule) {
            saveAgentState(sessionId, (StateModule) agent);
        }
    });
}
```

**子 Agent 特点**：
- 独立的会话状态管理
- 支持多轮对话
- 通过 `StateModule` 接口持久化/恢复状态

---

### 4.8 Session 与状态管理

#### 4.8.1 Session 接口

```java
// Session.java:53-145
public interface Session {
    void save(SessionKey sessionKey, String key, State value);
    void save(SessionKey sessionKey, String key, List<? extends State> values);
    <T extends State> Optional<T> get(SessionKey sessionKey, String key, Class<T> type);
    <T extends State> List<T> getList(SessionKey sessionKey, String key, Class<T> itemType);
    boolean exists(SessionKey sessionKey);
    void delete(SessionKey sessionKey);
}
```

#### 4.8.2 SessionPersistenceHook

```java
// SessionPersistenceHook.java:68-84
private void autoSave(Agent agent) {
    RuntimeContext ctx = this.runtimeContext;  // 从 per-call context 获取
    if (ctx == null || ctx.getSession() == null || ctx.getSessionKey() == null) {
        return;
    }
    if (agent instanceof StateModule sm) {
        sm.saveTo(ctx.getSession(), ctx.getSessionKey());
    }
}
```

- **优先级 900**：在所有其他 Hook 之后执行
- **触发时机**：`PostCallEvent` 和 `ErrorEvent`
- **持久化内容**：Agent 实现了 `StateModule` 接口的状态

#### 4.8.3 Session 实现

**MySQLSession** (`MysqlSession.java`):
- Schema: `session_id`, `state_key`, `item_index`, `state_data`
- `save()` 使用 UPSERT SQL
- `save(List)` 使用 hash 检测增量追加

**RedisSession** (`RedisSession.java`):
- Key 结构: `{prefix}{sessionId}:{stateKey}`
- List Key: `{prefix}{sessionId}:{stateKey}:list`
- 支持 Jedis、Lettuce、Redisson 客户端

---

### 4.9 多 Agent 协作模式

#### 4.9.1 MsgHub 消息广播

```java
// MsgHub.java:323-333
private void resetSubscribers() {
    if (enableAutoBroadcast) {
        for (AgentBase agent : participants) {
            List<AgentBase> others = participants.stream()
                    .filter(a -> !a.equals(agent))
                    .collect(Collectors.toList());
            agent.resetSubscribers(name, others);
        }
    }
}
```

**使用示例**：
```java
try (MsgHub hub = MsgHub.builder()
        .name("StudentDiscussion")
        .participants(alice, bob, charlie)
        .announcement(announcement)
        .enableAutoBroadcast(true)
        .build()) {
    hub.enter().block();
    alice.call().block();  // Bob & Charlie 自动收到
    bob.call().block();    // Alice & Charlie 自动收到
}
```

#### 4.9.2 Pipeline 模式

**SequentialPipeline**：
```
Input → Agent1 → Agent2 → ... → AgentN → Output
```

**FanoutPipeline**：
```
Input → [Agent1, Agent2, ..., AgentN] → [Output1, Output2, ..., OutputN]
```
- `concurrent()`: `Flux.merge` 真正并行
- `sequential()`: `Flux.concat` 顺序执行

#### 4.9.3 Handoff 模式

基于状态驱动的路由，工具更新状态变量（如 `active_agent`）：

```java
// 转移工具
@Tool(name = "transfer_to_support", description = "Transfer to support agent")
public String transferToSupport(ToolContext toolContext) {
    ToolContextHelper.getStateForUpdate(toolContext).ifPresent(update ->
        update.put(AgentScopeStateConstants.ACTIVE_AGENT,
                   AgentScopeStateConstants.SUPPORT_AGENT));
    return "Transferred to support agent.";
}
```

---

### 4.10 Structured Output 实现

#### 4.10.1 工作原理

`StructuredOutputCapableAgent` 使用 **`generate_response` 工具模式**：

```java
// StructuredOutputCapableAgent.java:70
public static final String STRUCTURED_OUTPUT_TOOL_NAME = "generate_response";
```

**执行流程**：
1. 创建临时工具，JSON Schema 来自目标类
2. 注册到 toolkit
3. 添加 `StructuredOutputHook`
4. LLM 被强制调用 `generate_response` 工具
5. 提取结构化数据

#### 4.10.2 StructuredOutputHook

```java
// StructuredOutputHook.java:61-106
public <T extends HookEvent> Mono<T> onEvent(T event) {
    if (event instanceof PreReasoningEvent) {
        // TOOL_CHOICE 模式下，强制 tool_choice = "generate_response"
    } else if (event instanceof PostReasoningEvent) {
        // 检查是否调用了工具；若无，触发重试
    } else if (event instanceof PostActingEvent) {
        // generate_response 成功时，停止 agent 并收集元数据
    }
}
```

**两种模式**：
| 模式 | 机制 | 兼容性 |
|------|------|--------|
| `TOOL_CHOICE` | 强制 tool_choice 参数 | 仅支持模型：qwen3-max, gpt-4 |
| `PROMPT` | 提示引导 | 兼容旧模型，可能多次调用 |

---

### 4.11 生产级特性对比

| 特性 | JavaClaw | AgentScope Java |
|------|----------|----------------|
| **优雅关闭** | ❌ | ✅ |
| **可观测性** | ❌ | ✅ OpenTelemetry |
| **Session 持久化** | YAML 文件 | MySQL/Redis |
| **Workspace 管理** | 简单目录 | WorkspaceManager + 沙箱 |
| **调度器** | JobRunr | Quartz/XXL-Job |
| **服务发现** | ❌ | ✅ Nacos |
| **A2A 协议** | ❌ | ✅ |
| **消息队列** | ❌ | ✅ RocketMQ |

### 4.12 上下文溢出处理对比

#### JavaClaw

- **无自动处理**
- 超过窗口大小直接丢失早期消息
- 无 compact / retry 机制

#### AgentScope Java

```
Context Overflow
    │
    ├── CompactionHook 触发
    │
    ├── 1. MemoryFlushHook
    │     → memory/YYYY-MM-DD.md (追加日志)
    │     → sessions/<id>.log.jsonl (消息落盘)
    │
    ├── 2. LLM 压缩
    │     → 从历史消息提取关键事实
    │     → 生成摘要
    │
    └── 3. 重试
          → 用压缩后的上下文重试 LLM 调用
          → 若仍超限，触发 forceCompactAndRetry
```

### 4.13 文件系统依赖对比

#### 核心差异

| 维度 | JavaClaw | AgentScope Java |
|------|----------|----------------|
| **依赖程度** | **强制依赖** | 可选（5 个禁用开关）|
| **无文件系统运行** | ❌ 不支持 | ✅ 可配置禁用 |
| **存储后端** | 仅文件系统 | 多后端可插拔 |
| **分布式部署** | 需要 NFS 等共享存储 | 支持 Redis/MySQL 等分布式方案 |
| **最小运行模式** | 需要完整 workspace | 可纯内存运行 |

#### JavaClaw：强制文件系统

所有核心功能均基于文件系统，无任何替代方案（详见 2.8 节）：

| 组件 | 存储路径 | 无文件系统时的后果 |
|------|----------|-------------------|
| Chat Memory | `{workspace}/conversations/*.yaml` | 对话历史完全丢失 |
| Task 持久化 | `{workspace}/tasks/*.md` | 任务无法创建/调度 |
| 配置管理 | `application.private.yaml` | 配置修改不持久化 |
| Skills | `{workspace}/skills/*.md` | 自定义 Skills 无法加载 |
| H2 数据库 | `jdbc:h2:file:` | JobRunr 无法运行 |

#### AgentScope Java：可选文件系统（详见 3.12.2 节）

提供 5 个禁用开关，每个开关精确控制一项功能：

| 禁用方法 | 具体影响 |
|----------|----------|
| `disableFilesystemTools()` | 失去 6 个文件操作工具（read/write/edit/grep/glob/list）|
| `disableMemoryTools()` | 无法搜索历史记忆和会话（memory_search/session_search 等）|
| `disableMemoryHooks()` | 记忆不持久化，重启后丢失；无自动归档/合并 |
| `disableSessionPersistence()` | 状态不自动保存，需要手动调用 `saveTo()` |
| `disableWorkspaceContext()` | 丢失 AGENTS.md/MEMORY.md/KNOWLEDGE.md 注入；Agent 不知道自己是谁/在哪 |

**完整禁用后的能力**：Agent 只剩下核心 ReAct 推理能力（reasoning/acting 循环），无任何持久化、无记忆检索、无工作区感知。

#### 架构选择建议

| 需求 | JavaClaw | AgentScope Java |
|------|----------|----------------|
| 简单桌面/服务器助手 | ✅ 适合 | ⚠️ 过度设计 |
| 无文件系统环境（容器） | ❌ 不支持 | ✅ 支持 |
| 分布式/微服务架构 | ❌ 需要共享存储 | ✅ Redis/MySQL |
| 企业级长期记忆 | ⚠️ 受限 | ✅ 支持 Mem0/ReMe |
| 快速原型开发 | ✅ 简单易用 | ⚠️ 学习成本高 |

---

## 五、适用场景分析

### 5.1 JavaClaw 适用场景

| 场景 | 适合度 | 原因 |
|------|--------|------|
| 个人 AI 助手 | ⭐⭐⭐⭐⭐ | 简单、部署容易 |
| 快速原型验证 | ⭐⭐⭐⭐ | 代码量少，快速上手 |
| 单 Agent 应用 | ⭐⭐⭐⭐ | 架构简洁 |
| 需要语义记忆 | ⭐ | 不支持 |
| 企业级应用 | ⭐⭐ | 缺乏生产级特性 |
| 多 Agent 协作 | ⭐ | 不支持 |

### 5.2 AgentScope Java 适用场景

| 场景 | 适合度 | 原因 |
|------|--------|------|
| 企业级 Agent 应用 | ⭐⭐⭐⭐⭐ | 完整生产特性 |
| 需要语义记忆/RAG | ⭐⭐⭐⭐⭐ | 完整长期记忆 |
| 多 Agent 协作 | ⭐⭐⭐⭐⭐ | MsgHub + Pipeline |
| 需要细粒度控制 | ⭐⭐⭐⭐⭐ | Hook 系统 |
| 快速原型 | ⭐⭐ | 学习曲线较陡 |
| 极简需求 | ⭐⭐ | 功能较多 |

---

## 六、总结

### 6.1 核心差异

| 维度 | JavaClaw | AgentScope Java |
|------|----------|----------------|
| **架构** | 委托式（Spring AI） | 自研式 |
| **ReAct** | 委托 Spring AI | 完全自研 |
| **记忆检索** | 仅滑动窗口 | 语义 + FTS5 + 向量 |
| **扩展机制** | 插件 | Hook + Toolkit |
| **生产特性** | 基础 | 完整 |
| **代码规模** | ~15K 行 | ~100K+ 行 |

### 6.2 选择建议

| 如果你需要 | 选择 |
|-----------|------|
| 快速搭建个人助手 | **JavaClaw** |
| 企业级生产系统 | **AgentScope Java** |
| 语义记忆检索 | **AgentScope Java** |
| 简单可控的架构 | **JavaClaw** |
| 多 Agent 协作 | **AgentScope Java** |
| Spring 生态集成 | **JavaClaw** (Spring AI) |
| 完整可观测性 | **AgentScope Java** |

### 6.3 未来演进可能

**JavaClaw**：
- 可能引入 RAG 支持（通过 MCP 工具）
- 记忆系统可能升级为滑动窗口 + 简单向量检索

**AgentScope Java**：
- 可能进一步抽象核心推理逻辑
- 可能增加更多 LLM 提供商支持
- RAG 能力可能进一步增强

---

## 附录

### A. 关键文件参考（详细版）

#### JavaClaw

| 文件 | 说明 |
|------|------|
| `JavaClawConfiguration.java:93-111` | ChatClient 配置中心，Advisor 链配置 |
| `DefaultAgent.java:17-23` | Agent 封装，`respondTo()` 方法 |
| `MessageChatMemoryAdvisor.java` | 自定义记忆 advisors，去重逻辑 |
| `MessageWindowChatMemory.java:53-72` | 窗口化记忆实现 |
| `FileSystemChatMemoryRepository.java` | YAML 持久化 |

#### AgentScope Java

| 文件 | 说明 |
|------|------|
| `ReActAgent.java` | ReAct 核心实现 |
| `HarnessAgent.java:282-306` | `bindRuntimeContext()` 方法 |
| `HarnessAgent.java:227-259` | `forceCompactAndRetry()` 方法 |
| `RuntimeContext.java:34-52` | RuntimeContext 组件 |
| `Hook.java` | Hook 系统接口 |
| `Memory.java` | 记忆接口 |
| `AutoContextMemory.java:185-306` | 6 级压缩策略实现 |
| `Toolkit.java` | 工具注册与执行 |
| `ToolExecutor.java:166-264` | 工具执行器 |
| `ToolSchemaGenerator.java:49-93` | JSON Schema 生成 |
| `ToolGroupManager.java:231-245` | 工具组管理 |
| `McpClientManager.java:129-210` | MCP 客户端管理 |
| `McpTool.java:172-192` | MCP 工具执行 |
| `SubagentsHook.java:204-246` | 子 Agent 注入 |
| `SubAgentTool.java:131-205` | 子 Agent 执行 |
| `MsgHub.java:323-333` | MsgHub 订阅设置 |
| `SequentialPipeline.java` | 顺序 Pipeline |
| `FanoutPipeline.java` | 扇出 Pipeline |
| `Session.java:53-145` | Session 接口 |
| `SessionPersistenceHook.java:68-84` | Session 自动保存 |
| `MysqlSession.java` | MySQL Session 实现 |
| `RedisSession.java` | Redis Session 实现 |
| `StructuredOutputCapableAgent.java` | 结构化输出 Agent |
| `StructuredOutputHook.java` | 结构化输出 Hook |
| `WorkspaceManager.java` | Workspace 管理 |
| `AbstractFilesystem.java:44-169` | 文件系统接口 |
| `LocalFilesystem.java` | 本地文件系统实现 |

### B. LongTermMemory 实现对比

| 实现 | 文件 | 技术 |
|------|------|------|
| `Mem0LongTermMemory` | `agentscope-extensions-mem0/.../Mem0LongTermMemory.java` | Mem0 API（向量嵌入 + LLM 提取） |
| `ReMeLongTermMemory` | `agentscope-extensions-reme/.../ReMeLongTermMemory.java` | ReMe API（workspace-based） |
| `BailianLongTermMemory` | `agentscope-extensions-memory-bailian/.../` | 阿里云服务 |

**Mem0 检索**：
```java
// Mem0LongTermMemory.java:268-292
public Mono<String> retrieve(Msg msg) {
    Mem0SearchRequest request = buildSearchRequest(query);
    return client.search(request)
            .map(response ->
                response.getResults().stream()
                    .map(Mem0SearchResult::getMemory)
                    .collect(Collectors.joining("\n"))
            )
            .onErrorReturn("");  // 优雅降级
}
```

**ReMe 检索**：
```java
// ReMeLongTermMemory.java:209-244
public Mono<String> retrieve(Msg msg) {
    ReMeSearchRequest request = ReMeSearchRequest.builder()
            .workspaceId(userId)
            .query(query)
            .topK(5)
            .build();
    return client.search(request)
            .map(response ->
                response.getAnswer() != null ?
                    response.getAnswer() :  // 优先使用 answer
                    response.getMemories().stream()
                        .collect(Collectors.joining("\n"))
            );
}
```

### C. 文档链接

- JavaClaw: (内嵌于代码库)
- AgentScope Java: https://java.agentscope.io/
- AgentScope GitHub: https://github.com/agentscope-ai/agentscope-java

---

*报告生成时间：2026-05-24*
