# 企业级 Spring AI 智能体前端开发完整指南

**基于 AG-UI 协议 + Spring AI + React/CopilotKit 架构**

**文档版本**: v2.0  
**最后更新**: 2026-03-11  
**适用**: AG-UI Protocol 0.x, Spring AI 1.1.0+, Java 21, Spring Boot 3.2+, CopilotKit 1.50+

---

## 📋 目录

- [一、架构总览](#一架构总览)
- [二、关键澄清：不需要 AgentScope Java](#二关键澄清不需要-agentscope-java)
- [三、AG-UI 协议核心概念](#三ag-ui-协议核心概念)
- [四、后端实现 - Spring AI + AG-UI 协议层](#四后端实现---spring-ai--ag-ui-协议层)
- [五、前端实现 - React + CopilotKit](#五前端实现---react--copilotkit)
- [六、多模态能力扩展](#六多模态能力扩展)
- [七、生产部署指南](#七生产部署指南)
- [八、常见问题排查](#八常见问题排查)

---

## 一、架构总览

### 1.1 核心架构

```
┌──────────────────────────────────────────────────┐
│  React 前端 (CopilotKit v1.50+)                  │
│  - useAgent() hook 或 CopilotSidebar 组件         │
│  - 纯 UI 展示,零业务逻辑                          │
│  - 通过 AG-UI 协议与后端通信                      │
└────────────────┬─────────────────────────────────┘
                 │
                 │ HTTP POST + SSE (AG-UI 事件流)
                 │ ⚠️ 重要:这是标准协议,不是框架
                 │
┌────────────────▼─────────────────────────────────┐
│  Spring AI 后端 (Spring MVC + 虚拟线程)          │
│  - ChatClient + Advisor 责任链                   │
│  - ChatMemory (JDBC 持久化)                      │
│  - RAG (VectorStore)                             │
│  - Tool Calling (@Tool)                          │
│  - 自己实现 AG-UI 协议层 (约 200 行核心代码)      │
└────────────────┬─────────────────────────────────┘
                 │
┌────────────────▼─────────────────────────────────┐
│  数据存储层                                       │
│  - PostgreSQL (对话历史 + 应用数据)              │
│  - pgvector (RAG 向量存储)                        │
└──────────────────────────────────────────────────┘
```

### 1.2 职责边界

| 层次 | 负责内容 | 不负责内容 |
|------|---------|-----------| | **React 前端** | UI 渲染、用户输入、文件上传、展示流式文本、AG-UI 事件订阅 | ❌ 对话历史存储<br>❌ 会话管理<br>❌ LLM 调用<br>❌ 协议转换 |
| **Spring AI 后端** | 所有业务逻辑、对话记忆、RAG、工具调用、状态管理、AG-UI 事件发送 | ❌ UI 渲染<br>❌ 前端路由<br>❌ 复杂协议转换 |

### 1.3 为什么选这个架构?

✅ **职责清晰**:前端只管 UI,后端管所有业务逻辑  
✅ **零架构冲突**:不引入 MongoDB/Redis 等新存储,Spring AI 持有所有状态  
✅ **Spring AI 代码几乎不动**:现有 ChatClient + Advisor 链完全保留  
✅ **协议层轻量**:AG-UI 只是约定好的 SSE 事件名称 + JSON payload,核心实现 ~200 行  
✅ **可逐步演进**:先跑通对话,再加多模态、Agent 交互  
✅ **生产级 UI**:CopilotKit 内置 Markdown 渲染、代码高亮、Tool 可视化  

---

## 二、关键澄清:不需要 AgentScope Java

### 2.1 你的直觉是对的

**AgentScope Java 是阿里云的一套完整 Agent 编排框架**(类似 LangGraph 的 Java 版),你只是想借鉴它发送 SSE 事件的那 ~100 行代码,却要连带引入:

- 整个 AgentScope 框架
- Spring WebFlux (与你的 Spring MVC 架构冲突)
- 阿里云的 Agent 编排抽象

**完全不值得**。

### 2.2 正确的做法:自己实现协议层

AG-UI **不是框架,是协议**。它的核心本质是:

```
SSE 事件名称约定 + JSON payload 约定 = 协议
```

**你需要的只是:**

1. **事件 POJO 定义**(约 50 行):定义事件类型枚举和 payload 类
2. **SSE 编码器**(约 30 行):把事件对象序列化成 `event: XXX\ndata: {...}` 格式
3. **Spring AI 集成**(约 120 行):从 `ChatClient.stream()` 转换成 AG-UI 事件

**总共约 200 行代码,零外部依赖,零框架绑定**。

### 2.3 参考资料在哪里?

虽然官方主仓库 `ag-ui-protocol/ag-ui` 确实有 Java SDK (位于 `sdks/community/java`),但它**不是官方核心团队维护的**,而是社区贡献的 `Work-m8/ag-ui-4j` 项目合并进来的。

**实际情况:**

| 项目 | 状态 | 用途 |
|------|------|------|
| `ag-ui-protocol/ag-ui` 官方主仓库 | ✅ 协议规范、TypeScript/Python SDK | 看协议定义和事件规范 |
| `Work-m8/ag-ui-4j` | ✅ 社区 Java 实现 | 参考事件 POJO 和编码器实现 |
| AgentScope Java | ⚠️ 完整框架 | **不推荐**引入,只借鉴代码片段 |

**正确的借鉴策略:**

1. 看官方协议文档理解事件定义: https://docs.ag-ui.com/concepts/events
2. 参考 `Work-m8/ag-ui-4j` 的 Java 实现细节
3. **把事件类和编码器代码复制进你的项目**,不添加任何外部依赖
4. 用你现有的 `SseEmitter` + `ChatClient` 驱动事件流

---

## 三、AG-UI 协议核心概念

### 3.1 协议本质

AG-UI 是一个 **事件驱动的通信协议**,它定义了:

```
前端 POST 请求 → 后端建立 SSE 连接 → 后端发送约定格式的事件流
```

**不是:**
- ❌ 不是框架 (无需引入依赖)
- ❌ 不是 SDK (只是协议规范)
- ❌ 不是新的传输层 (就是标准 HTTP + SSE)

**是:**
- ✅ 约定好的事件名称 (如 `TEXT_MESSAGE_CONTENT`)
- ✅ 约定好的 JSON payload 结构
- ✅ 约定好的事件顺序和生命周期

### 3.2 AG-UI 标准事件类型 (完整列表,2026 年 3 月最新)

**经过事实核查,AG-UI 协议定义了以下标准事件:**

#### 生命周期事件 (Lifecycle Events)
| 事件类型 | 何时发送 | 必需? |
|---------|---------|-------|
| `RUN_STARTED` | Agent 开始处理 | ✅ 必需 |
| `RUN_FINISHED` | Agent 处理完成 | ✅ 必需 |
| `RUN_ERROR` | 运行出错 | 推荐 |
| `STEP_STARTED` | Agent 步骤开始 | 可选 |
| `STEP_FINISHED` | Agent 步骤完成 | 可选 |

#### 文本消息事件 (Text Message Events)
| 事件类型 | 何时发送 | 必需? |
|---------|---------|-------|
| `TEXT_MESSAGE_START` | 开始生成文本消息 | ✅ 必需 |
| `TEXT_MESSAGE_CONTENT` | 流式文本 token (多次) | ✅ 必需 |
| `TEXT_MESSAGE_END` | 文本消息结束 | ✅ 必需 |
| `TEXT_MESSAGE_CHUNK` | 便捷事件,自动展开为 START/CONTENT/END | 可选 |

#### 工具调用事件 (Tool Call Events)
| 事件类型 | 何时发送 | 必需? |
|---------|---------|-------|
| `TOOL_CALL_START` | 工具调用开始 | 推荐 |
| `TOOL_CALL_ARGS` | 流式工具参数 (多次) | 可选 |
| `TOOL_CALL_END` | 工具调用结束 | 推荐 |
| `TOOL_CALL_RESULT` | 工具执行结果 | 推荐 |

#### 推理过程事件 (Reasoning Events) ⚠️ 重要更新
| 事件类型 | 何时发送 | 状态 |
|---------|---------|------|
| `REASONING_START` | 推理阶段开始 | ✅ 当前标准 |
| `REASONING_MESSAGE_START` | 推理消息开始 | ✅ 当前标准 |
| `REASONING_MESSAGE_CONTENT` | 推理内容流式输出 | ✅ 当前标准 |
| `REASONING_MESSAGE_END` | 推理消息结束 | ✅ 当前标准 |
| `REASONING_MESSAGE_CHUNK` | 便捷事件 | ✅ 当前标准 |
| `REASONING_END` | 推理阶段结束 | ✅ 当前标准 |
| `REASONING_ENCRYPTED_VALUE` | 加密推理内容 | ✅ 当前标准 |
| ~~`THINKING_START`~~ | ~~推理开始 (旧)~~ | ⚠️ **已废弃** |
| ~~`THINKING_END`~~ | ~~推理结束 (旧)~~ | ⚠️ **已废弃** |
| ~~`THINKING_TEXT_MESSAGE_*`~~ | ~~推理内容 (旧)~~ | ⚠️ **已废弃** |

**⚠️ 重要迁移提示:**
- `THINKING_*` 系列事件已在 AG-UI 协议中**标记为废弃**
- 官方文档明确指出:**将在 v1.0.0 版本移除**
- 新实现必须使用 `REASONING_*` 事件
- 迁移路径: `THINKING_START` → `REASONING_START`, `THINKING_TEXT_MESSAGE_CONTENT` → `REASONING_MESSAGE_CONTENT`

#### 状态管理事件 (State Management Events)
| 事件类型 | 何时发送 | 必需? |
|---------|---------|-------|
| `STATE_SNAPSHOT` | 完整状态快照 | 可选 |
| `STATE_DELTA` | 状态增量更新 | 可选 |
| `MESSAGES_SNAPSHOT` | 消息历史快照 | 可选 |
| `ACTIVITY_SNAPSHOT` | 活动状态快照 | 可选 |
| `ACTIVITY_DELTA` | 活动状态增量更新 | 可选 |

#### 特殊事件 (Special Events)
| 事件类型 | 用途 | 必需? |
|---------|-----|-------|
| `CUSTOM` | 自定义应用事件 | 可选 |
| `RAW` | 透传外部系统事件 | 可选 |

**最小实现 (MVP):**

对于第一版 MVP,你**只需要实现 5 个核心事件**:

```java
RUN_STARTED
TEXT_MESSAGE_START
TEXT_MESSAGE_CONTENT  (流式输出,多次)
TEXT_MESSAGE_END
RUN_FINISHED
```

其他事件可以在需要时逐步添加。

### 3.3 事件 Payload 结构示例

**`RUN_STARTED` Payload:**
```json
{
  "runId": "run-12345",
  "threadId": "thread-abc",
  "timestamp": "2026-03-11T08:30:00Z"
}
```

**`TEXT_MESSAGE_CONTENT` Payload:**
```json
{
  "messageId": "msg-67890",
  "runId": "run-12345",
  "delta": "这是一段"
}
```

**`TEXT_MESSAGE_END` Payload:**
```json
{
  "messageId": "msg-67890",
  "runId": "run-12345",
  "content": "这是一段完整的回复内容",
  "timestamp": "2026-03-11T08:30:05Z"
}
```

**`TOOL_CALL_RESULT` Payload:**
```json
{
  "messageId": "msg-67890",
  "toolCallId": "tool-001",
  "content": "{\"weather\": \"sunny\", \"temp\": 25}",
  "role": "tool"
}
```

---

## 四、后端实现 - Spring AI + AG-UI 协议层

### 4.1 技术栈要求

- **Java 21** (支持虚拟线程)
- **Spring Boot 3.2+** (内置虚拟线程支持)
- **Spring AI 1.1.0+** (最新稳定版)
- **Spring MVC** (不是 WebFlux,用标准 `SseEmitter`)
- **PostgreSQL 14+** (用于 ChatMemory JDBC 持久化)

### 4.2 步骤 1:定义 AG-UI 事件类型

**`AgUiEventType.java`**

```java
package com.yourcompany.agent.agui;

/**
 * AG-UI 协议标准事件类型枚举
 * 参考: https://docs.ag-ui.com/concepts/events
 */
public enum AgUiEventType {
    // 生命周期事件
    RUN_STARTED,
    RUN_FINISHED,
    RUN_ERROR,
    STEP_STARTED,
    STEP_FINISHED,

    // 文本消息事件
    TEXT_MESSAGE_START,
    TEXT_MESSAGE_CONTENT,
    TEXT_MESSAGE_END,

    // 工具调用事件
    TOOL_CALL_START,
    TOOL_CALL_ARGS,
    TOOL_CALL_END,
    TOOL_CALL_RESULT,

    // 推理过程事件 (当前标准)
    REASONING_START,
    REASONING_MESSAGE_START,
    REASONING_MESSAGE_CONTENT,
    REASONING_MESSAGE_END,
    REASONING_MESSAGE_CHUNK,
    REASONING_END,
    REASONING_ENCRYPTED_VALUE,

    // 状态管理事件
    STATE_SNAPSHOT,
    STATE_DELTA,
    MESSAGES_SNAPSHOT,

    // 自定义事件
    RAW,
    CUSTOM
}
```

### 4.3 步骤 2:实现 SSE 事件发送器

**`AgUiEventEmitter.java`**

```java
package com.yourcompany.agent.agui;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * AG-UI 协议 SSE 事件发送器
 * 核心逻辑:将事件对象序列化为 SSE 格式
 */
public class AgUiEventEmitter {
    
    private final SseEmitter emitter;
    private final ObjectMapper mapper;

    public AgUiEventEmitter(SseEmitter emitter) {
        this.emitter = emitter;
        this.mapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * 发送 AG-UI 标准事件
     * 
     * @param eventType 事件类型 (映射到 SSE 的 "event:" 字段)
     * @param payload 事件 payload (映射到 SSE 的 "data:" 字段)
     */
    public void sendEvent(AgUiEventType eventType, Map<String, Object> payload) {
        try {
            emitter.send(
                SseEmitter.event()
                    .name(eventType.name())  // event: RUN_STARTED
                    .data(mapper.writeValueAsString(payload))  // data: {...}
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to send AG-UI event: " + eventType, e);
        }
    }

    /**
     * 完成流式传输
     */
    public void complete() {
        emitter.complete();
    }

    /**
     * 发送错误并关闭连接
     */
    public void completeWithError(Throwable ex) {
        emitter.completeWithError(ex);
    }
}
```

**就这么简单!核心逻辑只有 30 行。**

### 4.4 步骤 3:实现 AG-UI 协议端点

**`AgUiController.java`**

```java
package com.yourcompany.agent.web;

import com.yourcompany.agent.agui.AgUiEventEmitter;
import com.yourcompany.agent.agui.AgUiEventType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;

/**
 * AG-UI 协议端点:CopilotKit 前端直连
 * 
 * 这个 Controller 的职责:
 * 1. 接收前端的 AG-UI 格式请求
 * 2. 调用 Spring AI 的 ChatClient 处理
 * 3. 将 ChatClient 的流式输出转换为 AG-UI 事件流
 */
@RestController
@RequestMapping("/api/agui")
@CrossOrigin(origins = "http://localhost:3000")  // 开发环境,生产环境改为实际域名
public class AgUiController {

    private final ChatClient chatClient;

    public AgUiController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * AG-UI 协议主端点
     * 
     * 请求格式:
     * POST /api/agui
     * Content-Type: application/json
     * Accept: text/event-stream
     * 
     * {
     *   "threadId": "thread-123",  // 可选,用于多轮对话
     *   "messages": [
     *     { "role": "user", "content": "你好" }
     *   ]
     * }
     * 
     * 响应:SSE 事件流
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody AgUiRequest request) {
        
        // 1. 创建 SSE 发送器
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时
        AgUiEventEmitter eventEmitter = new AgUiEventEmitter(emitter);
        
        // 2. 生成 ID
        String threadId = request.getThreadId() != null && !request.getThreadId().isBlank()
                ? request.getThreadId()
                : "thread-" + UUID.randomUUID();
        String runId = "run-" + UUID.randomUUID();
        String messageId = "msg-" + UUID.randomUUID();
        
        // 3. 提取用户消息 (取最后一条 user 消息)
        String userMessage = request.getMessages().stream()
            .filter(m -> "user".equals(m.getRole()))
            .reduce((first, second) -> second)
            .map(AgUiMessage::getContent)
            .orElse("");

        // 4. 在虚拟线程中执行流式调用
        Thread.ofVirtual().start(() -> {
            try {
                // 4.1 发送 RUN_STARTED 事件
                eventEmitter.sendEvent(AgUiEventType.RUN_STARTED, Map.of(
                    "runId", runId,
                    "threadId", threadId,
                    "timestamp", Instant.now().toString()
                ));

                // 4.2 发送 TEXT_MESSAGE_START 事件
                eventEmitter.sendEvent(AgUiEventType.TEXT_MESSAGE_START, Map.of(
                    "messageId", messageId,
                    "runId", runId,
                    "role", "assistant",
                    "timestamp", Instant.now().toString()
                ));

                // 4.3 调用 Spring AI ChatClient 流式输出
                StringBuilder fullContent = new StringBuilder();
                
                chatClient.prompt()
                    .user(userMessage)
                    .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, threadId))
                    .stream()
                    .content()
                    .toIterable()  // Flux → Iterable,虚拟线程友好
                    .forEach(token -> {
                        fullContent.append(token);
                        
                        // 4.4 发送 TEXT_MESSAGE_CONTENT 事件 (每个 token)
                        eventEmitter.sendEvent(AgUiEventType.TEXT_MESSAGE_CONTENT, Map.of(
                            "messageId", messageId,
                            "runId", runId,
                            "delta", token
                        ));
                    });

                // 4.5 发送 TEXT_MESSAGE_END 事件
                eventEmitter.sendEvent(AgUiEventType.TEXT_MESSAGE_END, Map.of(
                    "messageId", messageId,
                    "runId", runId,
                    "content", fullContent.toString(),
                    "timestamp", Instant.now().toString()
                ));

                // 4.6 发送 RUN_FINISHED 事件
                eventEmitter.sendEvent(AgUiEventType.RUN_FINISHED, Map.of(
                    "runId", runId,
                    "threadId", threadId,
                    "status", "completed"
                ));

                // 4.7 完成 SSE 连接
                eventEmitter.complete();

            } catch (Exception e) {
                // 4.8 错误处理
                eventEmitter.sendEvent(AgUiEventType.RUN_ERROR, Map.of(
                    "runId", runId,
                    "error", e.getMessage(),
                    "timestamp", Instant.now().toString()
                ));
                eventEmitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * AG-UI 标准请求体
     */
    public static class AgUiRequest {
        private String threadId;
        private List<AgUiMessage> messages;
        private Map<String, Object> properties;

        // Getters and Setters
        public String getThreadId() { return threadId; }
        public void setThreadId(String t) { this.threadId = t; }
        public List<AgUiMessage> getMessages() { return messages; }
        public void setMessages(List<AgUiMessage> m) { this.messages = m; }
        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> p) { this.properties = p; }
    }

    public static class AgUiMessage {
        private String role;
        private String content;
        private String messageId;

        // Getters and Setters
        public String getRole() { return role; }
        public void setRole(String r) { this.role = r; }
        public String getContent() { return content; }
        public void setContent(String c) { this.content = c; }
        public String getMessageId() { return messageId; }
        public void setMessageId(String m) { this.messageId = m; }
    }
}
```

### 4.5 步骤 4:启用虚拟线程

**`application.yml`**

```yaml
spring:
  threads:
    virtual:
      enabled: true  # Java 21 + Spring Boot 3.2+ 必需
  
  ai:
    chat:
      memory:
        repository:
          jdbc:
            initialize-schema: always  # 开发环境,生产改为 never
            table-name: chat_memory

server:
  port: 8080
```

### 4.6 步骤 5:确认 ChatClient 配置 (保持现有代码)

**`AiConfig.java`**

```java
@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(
            ChatModel chatModel,
            ChatMemory chatMemory,
            VectorStore vectorStore) {
        
        return ChatClient.builder(chatModel)
            .defaultAdvisors(
                // 1. 多轮对话记忆
                MessageChatMemoryAdvisor.builder(chatMemory)
                    .maxMessages(10)  // 限制历史消息数量,避免 token 浪费
                    .build(),
                
                // 2. RAG 增强
                QuestionAnswerAdvisor.builder(vectorStore)
                    .searchSimilarityThreshold(0.7)
                    .maxResults(5)
                    .build()
                
                // 3. 工具调用 (如果有 @Tool 方法)
                // new ToolCallAdvisor()
            )
            .build();
    }
}
```

**✅ 到此为止,后端 AG-UI 协议层已完成!**

**代码统计:**
- 事件类型枚举: ~50 行
- SSE 发送器: ~30 行
- Controller 端点: ~120 行
- **总计: ~200 行核心代码,零外部依赖**

---

## 五、前端实现 - React + CopilotKit

### 5.1 初始化前端项目

```bash
# 1. 创建 React + TypeScript 项目
npx create-react-app enterprise-agent-ui --template typescript
cd enterprise-agent-ui

# 2. 安装 CopilotKit 依赖
npm install @copilotkit/react-core @copilotkit/react-ui

# 3. 安装 UI 组件库 (可选,美化用)
npm install @radix-ui/themes
```

### 5.2 方案一:使用 CopilotKit 预制组件 (最快,推荐)

**`src/App.tsx`**

```tsx
import React from "react";
import { CopilotKit } from "@copilotkit/react-core";
import { CopilotSidebar } from "@copilotkit/react-ui";
import "@copilotkit/react-ui/styles.css";

function App() {
  return (
    <CopilotKit
      // ⚠️ 关键:直接连接 Spring AI 后端 AG-UI 端点
      runtimeUrl="http://localhost:8080/api/agui"
      
      // 可选:携带认证 token
      headers={{
        "Authorization": `Bearer ${localStorage.getItem('jwt') || ''}`
      }}
    >
      <CopilotSidebar
        labels={{
          title: "企业智能助手",
          initial: "你好!我可以帮你查询企业知识库、执行任务。有什么可以帮到你?",
          placeholder: "输入你的问题..."
        }}
        
        defaultOpen={true}
        clickOutsideToClose={false}
        
        // 自定义图标
        icons={{
          openIcon: "💬",
          closeIcon: "✕"
        }}
      >
        {/* 你的业务页面 */}
        <MainContent />
      </CopilotSidebar>
    </CopilotKit>
  );
}

function MainContent() {
  return (
    <div style={{ 
      padding: '40px', 
      maxWidth: '1200px', 
      margin: '0 auto',
      fontFamily: 'system-ui, sans-serif'
    }}>
      <h1>企业智能体系统</h1>
      <p>点击右侧聊天面板开始对话</p>
      
      <div style={{ marginTop: '32px' }}>
        <h2>系统能力</h2>
        <ul>
          <li>✅ 多轮对话记忆 (Spring AI ChatMemory)</li>
          <li>✅ RAG 知识库检索 (VectorStore)</li>
          <li>✅ 工具调用可视化 (@Tool)</li>
          <li>✅ 流式回复 (AG-UI SSE)</li>
        </ul>
      </div>
    </div>
  );
}

export default App;
```

### 5.3 方案二:使用 `useAgent` hook (完全自定义 UI)

**适用场景**:需要完全自定义聊天界面、不使用预制组件

**`src/App.tsx`**

```tsx
import React, { useState } from "react";
import { CopilotKit } from "@copilotkit/react-core";
import { useAgent } from "@copilotkit/react-core";

function App() {
  return (
    <CopilotKit runtimeUrl="http://localhost:8080/api/agui">
      <CustomChatUI />
    </CopilotKit>
  );
}

function CustomChatUI() {
  const [input, setInput] = useState("");
  
  // useAgent hook:完全控制 Agent 交互
  const {
    agent,
    visibleMessages,
    isRunning,
    run,
    stop
  } = useAgent({
    agentId: "default"  // 可选,多 Agent 时用
  });

  const handleSend = async () => {
    if (!input.trim() || isRunning) return;
    
    await run({ message: input });
    setInput("");
  };

  return (
    <div style={{ 
      display: 'flex', 
      flexDirection: 'column', 
      height: '100vh',
      fontFamily: 'system-ui, sans-serif'
    }}>
      {/* 头部 */}
      <header style={{ 
        padding: '16px', 
        background: '#111827', 
        color: 'white',
        fontWeight: 600
      }}>
        企业智能助手
      </header>

      {/* 消息列表 */}
      <div style={{ 
        flex: 1, 
        overflowY: 'auto', 
        padding: '16px',
        background: '#f3f4f6'
      }}>
        {visibleMessages.map((msg, idx) => (
          <div 
            key={idx}
            style={{
              marginBottom: '12px',
              padding: '12px',
              borderRadius: '8px',
              background: msg.role === 'user' ? '#3b82f6' : 'white',
              color: msg.role === 'user' ? 'white' : 'black',
              maxWidth: '70%',
              marginLeft: msg.role === 'user' ? 'auto' : '0',
              marginRight: msg.role === 'user' ? '0' : 'auto'
            }}
          >
            {msg.content}
          </div>
        ))}
        
        {isRunning && (
          <div style={{ padding: '12px', color: '#6b7280' }}>
            AI 正在思考...
          </div>
        )}
      </div>

      {/* 输入框 */}
      <div style={{ 
        padding: '16px', 
        borderTop: '1px solid #e5e7eb',
        display: 'flex',
        gap: '8px'
      }}>
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyPress={(e) => e.key === 'Enter' && handleSend()}
          placeholder="输入消息..."
          style={{
            flex: 1,
            padding: '12px',
            border: '1px solid #d1d5db',
            borderRadius: '8px',
            fontSize: '14px'
          }}
          disabled={isRunning}
        />
        <button
          onClick={handleSend}
          disabled={isRunning || !input.trim()}
          style={{
            padding: '12px 24px',
            background: '#3b82f6',
            color: 'white',
            border: 'none',
            borderRadius: '8px',
            cursor: isRunning ? 'not-allowed' : 'pointer',
            opacity: isRunning ? 0.5 : 1
          }}
        >
          {isRunning ? '发送中...' : '发送'}
        </button>
      </div>
    </div>
  );
}

export default App;
```

### 5.4 前端高级功能 (可选)

#### 5.4.1 让 AI 操控页面元素 (Human-in-the-Loop)

```tsx
import { useCopilotAction } from "@copilotkit/react-core";

function DataDashboard() {
  const [filter, setFilter] = React.useState<"all" | "active" | "completed">("all");

  // 注册一个前端 Action
  useCopilotAction({
    name: "changeFilter",
    description: "改变数据面板的筛选条件",
    parameters: [
      {
        name: "filterValue",
        type: "string",
        description: "筛选值:all, active, completed",
        enum: ["all", "active", "completed"]
      }
    ],
    handler: async ({ filterValue }) => {
      setFilter(filterValue as any);
      return `筛选器已更改为:${filterValue}`;
    },
    
    // 可选:需要用户确认
    renderAndWait: ({ args, status, respond }) => (
      <div style={{ 
        padding: '16px', 
        background: '#fef3c7',
        border: '1px solid #fbbf24',
        borderRadius: '8px'
      }}>
        <p>AI 请求更改筛选器到「{args.filterValue}」</p>
        <div style={{ marginTop: '8px', display: 'flex', gap: '8px' }}>
          <button onClick={() => respond("confirmed")}>确认</button>
          <button onClick={() => respond("rejected")}>拒绝</button>
        </div>
      </div>
    )
  });

  return (
    <div>
      <h2>数据面板 (当前筛选:{filter})</h2>
      {/* 用户可以说:"帮我把筛选改为 active" */}
    </div>
  );
}
```

#### 5.4.2 给 AI 注入页面上下文

```tsx
import { useCopilotReadable } from "@copilotkit/react-core";

function OrderList() {
  const orders = [
    { id: 1, amount: 1200, status: "pending" },
    { id: 2, amount: 800, status: "completed" }
  ];

  // AI 自动感知页面数据
  useCopilotReadable({
    description: "当前用户查看的订单列表,包含订单 ID、金额、状态",
    value: orders
  });

  return (
    <div>
      <h2>订单列表</h2>
      {/* 用户可以直接问:"有多少待处理订单?" */}
      {orders.map(o => (
        <div key={o.id}>
          订单 {o.id} - ¥{o.amount} - {o.status}
        </div>
      ))}
    </div>
  );
}
```

---

## 六、多模态能力扩展

### 6.1 图片上传

#### 6.1.1 后端文件上传接口

**`FileController.java`**

```java
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:3000")
public class FileController {

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        try {
            // 保存文件 (本地或对象存储)
            String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Path uploadPath = Paths.get("uploads");
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }
            Path filePath = uploadPath.resolve(fileName);
            Files.copy(file.getInputStream(), filePath);

            // 返回 URL
            String fileUrl = "http://localhost:8080/uploads/" + fileName;
            
            return Map.of(
                "success", true,
                "url", fileUrl,
                "name", file.getOriginalFilename(),
                "contentType", file.getContentType(),
                "size", file.getSize()
            );
        } catch (Exception e) {
            return Map.of(
                "success", false,
                "error", e.getMessage()
            );
        }
    }
    
    // 提供静态文件访问
    @GetMapping("/uploads/{filename}")
    public ResponseEntity<Resource> getFile(@PathVariable String filename) {
        try {
            Path file = Paths.get("uploads").resolve(filename);
            Resource resource = new UrlResource(file.toUri());
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
```

#### 6.1.2 Spring AI 处理图片消息

**修改 `AgUiController.java`,支持 Vision 模型**

```java
// 检测消息中是否包含图片
String imageUrl = null;
if (request.getProperties() != null && request.getProperties().containsKey("imageUrl")) {
    imageUrl = (String) request.getProperties().get("imageUrl");
}

if (imageUrl != null) {
    // 构造包含图片的 UserMessage
    UserMessage userMessageWithImage = new UserMessage(
        userMessage,
        List.of(new Media(MimeTypeUtils.IMAGE_PNG, new URL(imageUrl)))
    );
    
    chatClient.prompt()
        .messages(userMessageWithImage)
        .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, threadId))
        .stream()
        .content()
        .toIterable()
        .forEach(token -> {
            // 流式输出
        });
} else {
    // 纯文本消息
    chatClient.prompt()
        .user(userMessage)
        // ...
}
```

### 6.2 语音输入 (可选)

#### 6.2.1 后端音频转文字接口

**需要 OpenAI Whisper 或其他语音识别服务**

```java
@PostMapping("/transcribe")
public Map<String, Object> transcribe(@RequestParam("file") MultipartFile audioFile) {
    try {
        // 使用 Spring AI 的 OpenAI Whisper 集成
        OpenAiAudioTranscriptionModel transcriptionModel = 
            new OpenAiAudioTranscriptionModel(openAiApi);
        
        AudioTranscriptionPrompt prompt = new AudioTranscriptionPrompt(
            new ByteArrayResource(audioFile.getBytes())
        );
        
        AudioTranscriptionResponse response = transcriptionModel.call(prompt);
        String text = response.getResult().getOutput();
        
        return Map.of(
            "success", true,
            "text", text
        );
    } catch (Exception e) {
        return Map.of(
            "success", false,
            "error", e.getMessage()
        );
    }
}
```

---

## 七、生产部署指南

### 7.1 后端生产配置

**`application-prod.yml`**

```yaml
spring:
  threads:
    virtual:
      enabled: true
  
  ai:
    chat:
      memory:
        repository:
          jdbc:
            table-name: chat_memory
            initialize-schema: never  # 生产环境手动建表
    
  datasource:
    url: jdbc:postgresql://${DB_HOST}:5432/${DB_NAME}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5

server:
  port: 8080
  compression:
    enabled: true
  
logging:
  level:
    org.springframework.ai: INFO
    com.yourcompany: INFO
```

### 7.2 前端生产配置

**`.env.production`**

```
REACT_APP_AGUI_URL=https://api.your-company.com/api/agui
REACT_APP_UPLOAD_URL=https://api.your-company.com/api/upload
```

**`src/App.tsx` 生产环境**

```tsx
<CopilotKit
  runtimeUrl={process.env.REACT_APP_AGUI_URL || "http://localhost:8080/api/agui"}
  headers={{
    "Authorization": `Bearer ${localStorage.getItem('jwt')}`
  }}
>
```

### 7.3 Docker 部署

**后端 `Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/agent-backend.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

**前端 `Dockerfile`**

```dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/build /usr/share/nginx/html
COPY nginx.conf /etc/nginx/nginx.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

**`docker-compose.yml`**

```yaml
version: '3.8'

services:
  backend:
    build: ./spring-ai-backend
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - DB_HOST=postgres
      - DB_NAME=agent_db
      - DB_USER=agent
      - DB_PASSWORD=${DB_PASSWORD}
      - OPENAI_API_KEY=${OPENAI_API_KEY}
    depends_on:
      - postgres

  frontend:
    build: ./enterprise-agent-ui
    ports:
      - "3000:80"
    environment:
      - REACT_APP_AGUI_URL=http://backend:8080/api/agui

  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: agent_db
      POSTGRES_USER: agent
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./init.sql:/docker-entrypoint-initdb.d/init.sql
    ports:
      - "5432:5432"

volumes:
  pgdata:
```

---

## 八、常见问题排查

### 8.1 前端问题

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| 前端报 CORS 错误 | 后端未配置 CORS | 检查 `@CrossOrigin` 注解的 origins |
| 消息不显示 | SSE 连接失败 | 浏览器 Network 面板查看 `/api/agui` 请求状态 |
| 连接后立即断开 | 后端异常 | 查看后端日志,检查 ChatClient 配置 |
| "useAgent is not a function" | 导入路径错误 | 确认从 `@copilotkit/react-core` 导入 |

### 8.2 后端问题

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| SSE 不流式输出 | 没有用 `.stream()` | 确认用 `chatClient.stream().content().toIterable()` |
| 对话无历史记忆 | conversationId 未传递 | 检查前后端 threadId 是否一致 |
| 虚拟线程不生效 | Java 版本不对 | 确认 Java 21 + Spring Boot 3.2+ |
| 事件未发送 | SSE 编码错误 | 检查 `SseEmitter.send()` 调用是否抛异常 |

### 8.3 性能问题

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| 大量并发连接卡顿 | 平台线程不足 | 确认虚拟线程已启用 (`spring.threads.virtual.enabled=true`) |
| Token 消耗过高 | ChatMemory 历史过长 | 限制 `MessageChatMemoryAdvisor` 的 `maxMessages` 参数 |
| RAG 检索慢 | 向量索引未优化 | 在 VectorStore 上创建索引 |

---

## 附录 A:完整启动流程

### 开发环境启动

```bash
# 1. 启动 PostgreSQL (如果本地没有)
docker run -d \
  --name postgres-agent \
  -e POSTGRES_DB=agent_db \
  -e POSTGRES_USER=agent \
  -e POSTGRES_PASSWORD=password \
  -p 5432:5432 \
  pgvector/pgvector:pg16

# 2. 启动后端
cd spring-ai-backend
mvn spring-boot:run

# 3. 启动前端 (新终端)
cd enterprise-agent-ui
npm start

# 4. 访问 http://localhost:3000
```

### 生产环境启动

```bash
# 1. 配置环境变量
export DB_PASSWORD=your-secure-password
export OPENAI_API_KEY=sk-...

# 2. 启动所有服务
docker-compose up -d

# 3. 查看日志
docker-compose logs -f

# 4. 访问 https://your-domain.com
```

---

## 附录 B:工具调用可视化 (高级功能)

如果你的 Agent 使用了 `@Tool`,可以在前端显示工具调用过程:

**后端自定义 Advisor:**

```java
public class ToolCallVisualizationAdvisor implements CallAroundAdvisor {
    
    private final AgUiEventEmitter eventEmitter;
    
    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        AdvisedResponse response = chain.nextAroundCall(request);
        
        // 检查响应中是否有工具调用
        List<ToolCall> toolCalls = extractToolCalls(response);
        
        for (ToolCall toolCall : toolCalls) {
            // 发送 TOOL_CALL_START 事件
            eventEmitter.sendEvent(AgUiEventType.TOOL_CALL_START, Map.of(
                "toolCallId", toolCall.id(),
                "toolName", toolCall.name(),
                "input", toolCall.arguments()
            ));
            
            // 工具执行完后发送 TOOL_CALL_RESULT
            eventEmitter.sendEvent(AgUiEventType.TOOL_CALL_RESULT, Map.of(
                "messageId", getCurrentMessageId(),
                "toolCallId", toolCall.id(),
                "content", toolCall.result()
            ));
        }
        
        return response;
    }
}
```

---

## 附录 C:AG-UI 协议完整事件流示例

**完整的工具调用事件流:**

```
RUN_STARTED
  └── TEXT_MESSAGE_START (role: assistant)
  └── TEXT_MESSAGE_CONTENT × N  (流式 token)
  └── TEXT_MESSAGE_END
  └── TOOL_CALL_START           (toolCallId, toolName)
  └── TOOL_CALL_ARGS × N        (流式 JSON 参数 delta)
  └── TOOL_CALL_END             (toolCallId)
  └── TOOL_CALL_RESULT          (toolCallId, result)
  └── TEXT_MESSAGE_START (role: assistant, 工具调用后继续回复)
  └── TEXT_MESSAGE_CONTENT × N
  └── TEXT_MESSAGE_END
RUN_FINISHED
```

---

## 附录 D:事实核查总结

**本指南经过以下事实核查:**

1. ✅ **AG-UI 协议定义**:参考官方文档 https://docs.ag-ui.com/concepts/events
2. ✅ **事件类型清单**:核实了 2026 年 3 月最新的标准事件列表
3. ✅ **REASONING vs THINKING**:确认 `THINKING_*` 事件已标记为废弃,`REASONING_*` 是当前标准
4. ✅ **Java SDK 状态**:确认 `Work-m8/ag-ui-4j` 是社区贡献项目,已合并进官方主仓库
5. ✅ **AgentScope Java**:确认它是完整框架,不适合只为协议层引入
6. ✅ **CopilotKit 集成**:确认 v1.50+ 原生支持 AG-UI 协议

---

## 结语

这个方案的核心优势:

✅ **协议层轻量**:~200 行核心代码,零外部依赖  
✅ **职责清晰**:前端只管 UI,后端管所有逻辑和状态  
✅ **零架构冲突**:不引入新的存储,Spring AI 持有所有状态  
✅ **Spring AI 代码几乎不动**:现有 ChatClient + Advisor 链完全保留  
✅ **可逐步演进**:先跑通对话,再加多模态、高级交互  
✅ **生产级 UI**:CopilotKit 内置专业 UI 组件  
✅ **标准协议**:AG-UI 是开放标准,不绑定任何框架  

团队可以按照本指南,从最小实现 (5 个核心事件) 开始,逐步迭代到生产级企业智能体前端。

**关键记住:**
- AG-UI 是协议,不是框架
- 不需要 AgentScope Java
- 自己实现协议层只需 ~200 行代码
- 优先使用 `REASONING_*` 事件,避免已废弃的 `THINKING_*` 事件

---

**参考资源:**

- AG-UI 协议官方文档: https://docs.ag-ui.com
- AG-UI GitHub 主仓库: https://github.com/ag-ui-protocol/ag-ui
- Work-m8/ag-ui-4j (参考实现): https://github.com/Work-m8/ag-ui-4j
- CopilotKit 文档: https://docs.copilotkit.ai
- Spring AI 文档: https://docs.spring.io/spring-ai/reference/

---

**文档维护者**: AI Research Team  
**联系方式**: [你的联系方式]  
**许可证**: MIT License
