# Enterprise Agent 前端实现完整指引 v4.0

> **版本**: v4.0（2026-03-11）  
> **技术栈**: Spring AI (Java 后端) + CopilotKit Runtime (Node.js BFF 薄层) + Next.js 15 + React  
> **核心原则**: 业务逻辑全在 Java，Node.js 中间层仅作 BFF/代理层，采用增量修改保护现有后端

---

## 📋 目录

1. [架构概览](#第零章架构概览)
2. [Java 后端实现](#第一章java-后端实现)
3. [Node.js BFF 层实现](#第二章nodejs-bff-层实现)
4. [Next.js 前端实现](#第三章nextjs-前端实现)
5. [部署与测试](#第四章部署与测试)
6. [常见问题](#第五章常见问题)
7. [附录：手写协议层方案](#附录手写协议层方案可选)

---

## 第零章:架构概览

### 0.1 三层架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│  【前端层】React + Next.js 15                                          │
│                                                                      │
│  <CopilotKit runtimeUrl="/api/copilotkit">                           │
│    选项A: <CopilotPopup />          ← 开箱即用,3行代码完整UI          │
│    选项B: <CopilotSidebar />        ← 侧边栏布局                      │
│    选项C: useAgent() + 自定义UI      ← 完全控制                       │
│  </CopilotKit>                                                       │
│                                                                      │
│  底层:ProxiedCopilotRuntimeAgent(CopilotKit自动创建,对组件透明)       │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ HTTP POST + SSE事件流(AG-UI协议)
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  【BFF层】Node.js / Next.js (CopilotKit Runtime) ← 薄层!             │
│                                                                      │
│  📄 app/api/copilotkit/route.ts  ← 仅此一个文件!约35行               │
│                                                                      │
│  ✅ 负责:                                                             │
│     • JWT验证/透传                                                    │
│     • Agent路由发现(/info端点)                                        │
│     • AG-UI Middleware(日志、guardrails)                             │
│     • SSE事件流代理(透传)                                             │
│     • Premium功能入口(Threads、Observability)                         │
│                                                                      │
│  ❌ 不做:                                                             │
│     • 任何AI推理 • 任何业务逻辑 • 任何LLM调用                         │
│                                                                      │
│  关键:serviceAdapter = new ExperimentalEmptyAdapter()                │
│       ↑ 无需任何LLM API Key!                                          │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ HTTP POST + SSE事件流(AG-UI协议)
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  【业务层】Spring AI Java 后端 ← 所有业务逻辑在这里!                   │
│                                                                      │
│  POST /api/agui                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  您现有的Agent逻辑 + 轻量级AG-UI协议适配:                      │    │
│  │    • ChatClient(Spring AI) → LLM调用                        │    │
│  │    • @Tool注解方法 → 工具调用                                │    │
│  │    • ChatMemory → 会话记忆                                  │    │
│  │    • RAG/向量检索/业务数据库访问                              │    │
│  │    • SseEmitter → SSE事件流输出                             │    │
│  └─────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

### 0.2 关键事实核查结论

基于官方文档核查,以下是确认的事实:

| 事项 | 结论 | 来源 |
|------|------|------|
| **BFF层是否需要LLM Key?** | ❌ 不需要,使用`ExperimentalEmptyAdapter()` | CopilotKit官方文档 |
| **前端能否直连Java后端?** | ⚠️ 仅供开发,生产不支持 | CopilotKit官方文档 |
| **Node.js层是否只是薄层?** | ✅ 是的,约35行代码,零业务逻辑 | CopilotKit官方文档 |
| **Java SDK是否官方提供?** | ⚠️ 社区贡献,尚未发布到Maven Central | ag-ui-protocol/ag-ui GitHub |
| **AG-UI协议核心是什么?** | SSE + 事件名称约定 + JSON payload | AG-UI官方文档 |

### 0.3 AG-UI事件流标准顺序

Java后端需要发射的完整事件流:

```
RUN_STARTED
  ├── TEXT_MESSAGE_START (role: assistant, messageId)
  ├── TEXT_MESSAGE_CONTENT × N (流式token)
  ├── TEXT_MESSAGE_END (messageId)
  │
  ├── TOOL_CALL_START (toolCallId, toolName) ← 有工具调用时
  ├── TOOL_CALL_ARGS × N (参数JSON chunks)
  ├── TOOL_CALL_END (toolCallId)
  ├── TOOL_CALL_RESULT (toolCallId, result)
  │
  ├── TEXT_MESSAGE_START (新messageId,工具后继续回复)
  ├── TEXT_MESSAGE_CONTENT × N
  └── TEXT_MESSAGE_END
RUN_FINISHED
```

---

## 第一章:Java 后端实现

### 核心策略:增量修改,保护现有后端

您已有的Spring AI后端**不需要大规模重构**,只需:
1. 添加一个新的Controller端点(`/api/agui`)
2. 添加一个SSE事件发射器工具类(约150行)
3. 在现有ChatClient流程中插入事件发射调用

### 1.1 项目结构

```
backend/ (您现有的Spring AI项目)
├── pom.xml                          ← 可选:添加社区SDK依赖
├── src/main/java/com/company/
│   ├── agent/
│   │   └── YourExistingAgent.java   ← 保持不变!
│   ├── controller/
│   │   ├── YourExistingController.java  ← 保持不变!
│   │   └── AgUiController.java       ← 新增:AG-UI端点
│   ├── agui/                         ← 新增目录
│   │   ├── AgUiEventType.java       ← 事件类型枚举
│   │   ├── AgUiEventEmitter.java    ← SSE发射器
│   │   └── AgUiService.java         ← 服务封装
│   └── config/
│       └── AgUiConfig.java          ← 新增:Bean配置
└── src/main/resources/
    └── application.yml               ← 添加CORS配置
```

### 1.2 方案选择

您有两个选择:

#### 选项A:使用社区SDK(简单但依赖未发布的包)

**优点**: 代码量少,维护成本低  
**缺点**: 需要手动从GitHub安装到本地Maven仓库

```xml
<!-- pom.xml -->
<dependencies>
    <!-- 现有依赖保持不变 -->
    
    <!-- 新增:AG-UI社区SDK -->
    <dependency>
        <groupId>com.ag-ui.community</groupId>
        <artifactId>spring</artifactId>
        <version>0.0.1</version>
    </dependency>
    
    <dependency>
        <groupId>com.ag-ui.community</groupId>
        <artifactId>spring-ai</artifactId>
        <version>1.0.1</version>
    </dependency>
</dependencies>
```

**安装方法**:
```bash
git clone https://github.com/ag-ui-protocol/ag-ui.git
cd ag-ui/sdks/community/java
mvn install -DskipTests
```

#### 选项B:手写协议层(推荐生产环境)

**优点**: 零外部依赖,完全自持,约150行代码  
**缺点**: 需要复制约3个Java类到项目中

**详见[附录:手写协议层方案](#附录手写协议层方案可选)**

### 1.3 实现步骤(基于选项B:手写方案)

#### Step 1.3.1:创建事件类型枚举

```java
// src/main/java/com/company/agui/AgUiEventType.java
package com.company.agui;

public enum AgUiEventType {
    // 生命周期事件
    RUN_STARTED,
    RUN_FINISHED,
    RUN_ERROR,
    
    // 文本消息事件
    TEXT_MESSAGE_START,
    TEXT_MESSAGE_CONTENT,
    TEXT_MESSAGE_END,
    
    // 工具调用事件
    TOOL_CALL_START,
    TOOL_CALL_ARGS,
    TOOL_CALL_END,
    TOOL_CALL_RESULT,
    
    // 状态同步事件(可选)
    STATE_SNAPSHOT,
    STATE_DELTA
}
```

#### Step 1.3.2:创建SSE事件发射器

```java
// src/main/java/com/company/agui/AgUiEventEmitter.java
package com.company.agui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.Map;

/**
 * AG-UI协议SSE事件发射器
 * 核心职责:将Java对象序列化为AG-UI协议要求的SSE事件格式
 */
public class AgUiEventEmitter {
    
    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;
    
    public AgUiEventEmitter(long timeout, ObjectMapper objectMapper) {
        this.emitter = new SseEmitter(timeout);
        this.objectMapper = objectMapper;
    }
    
    /**
     * 发送AG-UI协议事件
     * 格式: event: EVENT_TYPE\ndata: {json}\n\n
     */
    public void sendEvent(AgUiEventType eventType, Object payload) {
        try {
            String jsonData = objectMapper.writeValueAsString(payload);
            emitter.send(
                SseEmitter.event()
                    .name(eventType.name())  // event: RUN_STARTED
                    .data(jsonData)          // data: {"runId":"..."}
            );
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
    
    /**
     * 便捷方法:发送RUN_STARTED
     */
    public void sendRunStarted(String runId) {
        sendEvent(AgUiEventType.RUN_STARTED, Map.of("runId", runId));
    }
    
    /**
     * 便捷方法:发送TEXT_MESSAGE_START
     */
    public void sendTextMessageStart(String messageId, String role) {
        sendEvent(AgUiEventType.TEXT_MESSAGE_START, Map.of(
            "messageId", messageId,
            "role", role
        ));
    }
    
    /**
     * 便捷方法:发送TEXT_MESSAGE_CONTENT
     */
    public void sendTextMessageContent(String messageId, String content) {
        sendEvent(AgUiEventType.TEXT_MESSAGE_CONTENT, Map.of(
            "messageId", messageId,
            "content", content
        ));
    }
    
    /**
     * 便捷方法:发送TEXT_MESSAGE_END
     */
    public void sendTextMessageEnd(String messageId) {
        sendEvent(AgUiEventType.TEXT_MESSAGE_END, Map.of("messageId", messageId));
    }
    
    /**
     * 便捷方法:发送TOOL_CALL_START
     */
    public void sendToolCallStart(String toolCallId, String toolName) {
        sendEvent(AgUiEventType.TOOL_CALL_START, Map.of(
            "toolCallId", toolCallId,
            "toolName", toolName
        ));
    }
    
    /**
     * 便捷方法:发送TOOL_CALL_RESULT
     */
    public void sendToolCallResult(String toolCallId, Object result) {
        sendEvent(AgUiEventType.TOOL_CALL_RESULT, Map.of(
            "toolCallId", toolCallId,
            "result", result
        ));
    }
    
    /**
     * 便捷方法:发送RUN_FINISHED
     */
    public void sendRunFinished(String runId) {
        sendEvent(AgUiEventType.RUN_FINISHED, Map.of("runId", runId));
    }
    
    /**
     * 完成SSE流
     */
    public void complete() {
        emitter.complete();
    }
    
    /**
     * 获取底层SseEmitter
     */
    public SseEmitter getEmitter() {
        return emitter;
    }
}
```

#### Step 1.3.3:创建AG-UI服务

```java
// src/main/java/com/company/agui/AgUiService.java
package com.company.agui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import java.util.UUID;
import java.util.List;
import java.util.Map;

/**
 * AG-UI服务:连接您现有的Spring AI ChatClient与AG-UI协议
 */
@Service
public class AgUiService {
    
    private final ObjectMapper objectMapper;
    
    public AgUiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    /**
     * 运行您的Agent并返回AG-UI协议SSE流
     * 
     * @param chatClient 您现有的Spring AI ChatClient
     * @param requestBody AG-UI协议请求体(包含messages、threadId等)
     * @return SseEmitter
     */
    public AgUiEventEmitter runAgent(ChatClient chatClient, Map<String, Object> requestBody) {
        
        AgUiEventEmitter emitter = new AgUiEventEmitter(300_000L, objectMapper);
        
        // 提取请求参数
        String runId = UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = 
            (List<Map<String, Object>>) requestBody.get("messages");
        
        // 1. 发送RUN_STARTED
        emitter.sendRunStarted(runId);
        
        // 2. 发送TEXT_MESSAGE_START
        emitter.sendTextMessageStart(messageId, "assistant");
        
        // 3. 异步处理(避免阻塞Controller线程)
        new Thread(() -> {
            try {
                // 构建用户消息
                String userMessage = extractLastUserMessage(messages);
                
                // 调用您现有的ChatClient(流式)
                Flux<String> responseFlux = chatClient
                    .prompt()
                    .user(userMessage)
                    .stream()
                    .content();
                
                // 4. 流式发送TEXT_MESSAGE_CONTENT
                responseFlux.subscribe(
                    chunk -> emitter.sendTextMessageContent(messageId, chunk),
                    error -> emitter.sendEvent(AgUiEventType.RUN_ERROR, 
                        Map.of("error", error.getMessage())),
                    () -> {
                        // 5. 发送TEXT_MESSAGE_END
                        emitter.sendTextMessageEnd(messageId);
                        // 6. 发送RUN_FINISHED
                        emitter.sendRunFinished(runId);
                        emitter.complete();
                    }
                );
                
            } catch (Exception e) {
                emitter.sendEvent(AgUiEventType.RUN_ERROR, 
                    Map.of("error", e.getMessage()));
                emitter.complete();
            }
        }).start();
        
        return emitter;
    }
    
    /**
     * 从AG-UI请求中提取最后一条用户消息
     */
    private String extractLastUserMessage(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        
        // 倒序查找最后一条role=user的消息
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if ("user".equals(msg.get("role"))) {
                return (String) msg.get("content");
            }
        }
        
        return "";
    }
}
```

#### Step 1.3.4:创建AG-UI Controller

```java
// src/main/java/com/company/controller/AgUiController.java
package com.company.controller;

import com.company.agui.AgUiService;
import com.company.agui.AgUiEventEmitter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Map;

/**
 * AG-UI协议端点
 * 
 * CopilotKit Runtime的HttpAgent会POST到这里
 * 接收AG-UI请求,返回SSE事件流
 */
@RestController
@RequestMapping("/api/agui")
@CrossOrigin(origins = "${agui.allowed-origins:http://localhost:3000}")
public class AgUiController {
    
    private final AgUiService agUiService;
    private final ChatClient chatClient;
    
    public AgUiController(AgUiService agUiService, ChatClient chatClient) {
        this.agUiService = agUiService;
        this.chatClient = chatClient;
    }
    
    /**
     * AG-UI协议端点
     * 
     * @param requestBody AG-UI协议请求体
     * @param authHeader Authorization头(可选,用于JWT验证)
     * @return SSE事件流
     */
    @PostMapping(produces = "text/event-stream")
    public ResponseEntity<SseEmitter> run(
            @RequestBody Map<String, Object> requestBody,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        
        // 生产环境:在此验证JWT
        // if (authHeader != null) {
        //     jwtService.validate(authHeader);
        // }
        
        AgUiEventEmitter emitter = agUiService.runAgent(chatClient, requestBody);
        
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .header("X-Accel-Buffering", "no")  // 禁用Nginx缓冲
            .body(emitter.getEmitter());
    }
}
```

#### Step 1.3.5:配置文件

```yaml
# src/main/resources/application.yml

spring:
  ai:
    openai:                              # 或国内模型配置
      api-key: ${OPENAI_API_KEY}
      base-url: ${OPENAI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: gpt-4o

  mvc:
    async:
      request-timeout: -1                # 必须!禁用MVC异步超时

server:
  port: 8080

agui:
  allowed-origins: http://localhost:3000,https://your-prod-domain.com
```

#### Step 1.3.6:集成到现有Agent

如果您有现有的ChatClient配置,只需确保它被注入到AgUiController中:

```java
// 您现有的配置类
@Configuration
public class AgentConfig {
    
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
            .defaultSystem("您现有的系统提示词")
            .defaultTools(yourExistingTools())  // 您现有的工具
            .build();
    }
}
```

**就这样!** Java后端改造完成。您现有的业务逻辑**完全不需要改动**。

---

## 第二章:Node.js BFF 层实现

### 2.1 创建Next.js项目

```bash
npx create-next-app@latest frontend --typescript --tailwind --app
cd frontend

# 安装CopilotKit相关依赖
npm install @copilotkit/react-core @copilotkit/react-ui @copilotkit/runtime

# 安装AG-UI客户端库（必需！）
npm install @ag-ui/client@^0.0.47
```

**重要**: `@ag-ui/client` 必须安装，版本建议使用 `^0.0.47` 或更高版本。

### 2.2 创建BFF层(仅一个文件!)

```typescript
// app/api/copilotkit/route.ts

import {
  CopilotRuntime,
  copilotRuntimeNextJSAppRouterEndpoint,
} from "@copilotkit/runtime";
import { HttpAgent } from "@ag-ui/client";
import { NextRequest } from "next/server";

/**
 * CopilotKit Runtime - BFF层
 *
 * 职责:
 * ✅ 代理前端请求到Java后端
 * ✅ 透传Authorization头(如有)
 * ✅ 提供/info端点供Agent发现
 * ✅ 透传SSE事件流
 *
 * 不做:
 * ❌ 任何AI推理
 * ❌ 任何业务逻辑
 * ❌ 任何LLM调用
 *
 * 重要:
 * - 使用对象格式注册agents（不是数组）
 * - agent名称 "default" 会被CopilotPopup自动使用
 * - HttpAgent包装Java后端的AG-UI端点
 */

const JAVA_BACKEND_URL = process.env.JAVA_BACKEND_URL || "http://localhost:8080";

const runtime = new CopilotRuntime({
  agents: {
    // 使用 "default" 作为agent名称,CopilotPopup会自动使用它
    default: new HttpAgent({
      url: `${JAVA_BACKEND_URL}/api/agui`,
    }),
  },
});

const { handleRequest } = copilotRuntimeNextJSAppRouterEndpoint({
  runtime,
  endpoint: "/api/copilotkit",
});

export async function POST(req: NextRequest) {
  return handleRequest(req);
}
```

### 2.3 环境变量配置

```bash
# .env.local

# Java后端地址
JAVA_BACKEND_URL=http://localhost:8080

# 生产环境示例
# JAVA_BACKEND_URL=https://your-java-backend.com
```

**就这样!** BFF层完成,仅35行代码。

---

## 第三章:Next.js 前端实现

### 3.1 配置CopilotKit Provider

```typescript
// app/layout.tsx

import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";
import { CopilotKit } from "@copilotkit/react-core";
import "@copilotkit/react-ui/styles.css";

const inter = Inter({ subsets: ["latin"] });

export const metadata: Metadata = {
  title: "Enterprise Agent",
  description: "AI-powered enterprise assistant",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="zh-CN">
      <body className={inter.className}>
        {/* CopilotKit Provider - 指向BFF层 */}
        <CopilotKit runtimeUrl="/api/copilotkit">
          {children}
        </CopilotKit>
      </body>
    </html>
  );
}
```

### 3.2 使用CopilotKit UI组件

#### 选项A:使用CopilotPopup(最简单)

```typescript
// app/page.tsx

"use client";

import { CopilotPopup } from "@copilotkit/react-ui";

export default function Home() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center p-24">
      <h1 className="text-4xl font-bold mb-8">
        Enterprise Agent
      </h1>

      <p className="text-lg text-gray-600 mb-8">
        点击右下角图标开始对话
      </p>

      {/* 3行代码,完整聊天UI! */}
      <CopilotPopup
        instructions="你是企业智能助手，帮助员工解答业务问题、查询数据、执行操作。"
        labels={{
          title: "企业智能助手",
          initial: "你好!我是企业智能助手,可以帮你查询订单、检索知识库等。有什么可以帮助你的吗?",
          placeholder: "输入你的问题..."
        }}
      />
    </main>
  );
}
```

**注意**: 如果在BFF层注册了名为 "default" 的 agent，CopilotPopup 会自动使用它，无需指定 `agent` 属性。如果你想使用其他名称的 agent，需要添加 `agent="your-agent-name"` 属性。

#### 选项B:使用CopilotSidebar(侧边栏)

```typescript
// app/page.tsx

"use client";

import { CopilotSidebar } from "@copilotkit/react-ui";

export default function Home() {
  return (
    <div className="flex h-screen">
      {/* 主内容区 */}
      <main className="flex-1 p-8">
        <h1 className="text-3xl font-bold mb-4">业务工作台</h1>
        <p className="text-gray-600">您的业务内容...</p>
      </main>
      
      {/* 侧边栏Agent */}
      <CopilotSidebar
        defaultOpen={true}
        labels={{
          title: "企业智能助手",
          initial: "你好!我可以帮你查询数据、检索知识库。",
        }}
      />
    </div>
  );
}
```

#### 选项C:使用useAgent Hook(完全自定义)

```typescript
// app/page.tsx

"use client";

import { useAgent } from "@copilotkit/react-core";
import { useState } from "react";

export default function Home() {
  const [input, setInput] = useState("");

  // 连接到Agent - 使用 "default" agent名称
  const { agent } = useAgent({
    name: "default",
  });

  const handleSend = async () => {
    if (!input.trim()) return;

    // 添加用户消息
    agent.addMessage({
      id: `msg-${Date.now()}`,
      role: "user",
      content: input,
    });

    setInput("");

    // 运行Agent
    await agent.runAgent({
      runId: `run-${Date.now()}`,
    });
  };

  return (
    <div className="flex flex-col h-screen p-8">
      <h1 className="text-3xl font-bold mb-4">企业智能助手</h1>

      {/* 消息列表 */}
      <div className="flex-1 overflow-y-auto mb-4 space-y-4">
        {agent.messages.map((msg, idx) => (
          <div
            key={idx}
            className={`p-4 rounded-lg ${
              msg.role === "user"
                ? "bg-blue-100 ml-auto max-w-[80%]"
                : "bg-gray-100 mr-auto max-w-[80%]"
            }`}
          >
            <div className="font-semibold mb-1">
              {msg.role === "user" ? "你" : "助手"}
            </div>
            <div>{msg.content}</div>
          </div>
        ))}

        {agent.isRunning && (
          <div className="text-gray-500 animate-pulse">
            正在思考...
          </div>
        )}
      </div>

      {/* 输入框 */}
      <div className="flex gap-2">
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyPress={(e) => e.key === "Enter" && handleSend()}
          placeholder="输入消息..."
          className="flex-1 px-4 py-2 border rounded-lg"
          disabled={agent.isRunning}
        />
        <button
          onClick={handleSend}
          disabled={agent.isRunning || !input.trim()}
          className="px-6 py-2 bg-blue-500 text-white rounded-lg disabled:opacity-50"
        >
          发送
        </button>
      </div>
    </div>
  );
}
```

**注意事项**:
1. **agent名称**: 如果注册了名为 "default" 的 agent，CopilotPopup 会自动使用它，不需要指定 `agent` 属性
2. **自定义名称**: 如果想使用其他名称（如 "enterprise-agent"），需要：
   - 在 `CopilotPopup` 中指定 `agent="enterprise-agent"`
   - 或者在 `useAgent` 中指定 `name: "enterprise-agent"`
   - 但必须与 BFF 层注册的 agent 名称一致

3. **推荐做法**: 使用 "default" 作为 agent 名称，最简单，CopilotPopup 无需额外配置

```

---

## 第四章:部署与测试

### 4.1 本地开发测试

#### 启动Java后端

```bash
cd backend
mvn spring-boot:run

# 验证AG-UI端点
curl -X POST http://localhost:8080/api/agui \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"Hello"}]}'
```

#### 启动Next.js前端

```bash
cd frontend
npm run dev

# 访问 http://localhost:3000
```

### 4.2 测试工具调用

如果您的Java后端有@Tool方法,AG-UI会自动生成工具调用事件流。

示例Java工具:

```java
@Component
public class BusinessTools {
    
    @Tool(description = "查询订单详情")
    public String queryOrder(String orderId) {
        // 您的业务逻辑
        return "{\"orderId\":\"" + orderId + "\",\"status\":\"shipped\"}";
    }
}
```

前端会自动显示工具调用过程!

### 4.3 生产部署

#### Java后端(示例:Docker)

```dockerfile
FROM openjdk:17-jdk-slim
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app.jar"]
```

#### Next.js前端(Vercel/Netlify/Docker)

```bash
# Vercel部署(最简单)
npm install -g vercel
vercel

# 或Docker
docker build -t frontend .
docker run -p 3000:3000 -e JAVA_BACKEND_URL=https://your-api.com frontend
```

#### 环境变量(生产)

```bash
# 前端
JAVA_BACKEND_URL=https://your-java-backend.com

# 后端
AGUI_ALLOWED_ORIGINS=https://your-frontend.com
```

---

## 第五章:常见问题

### Q1: 为什么需要Node.js中间层?不能前端直连Java吗?

**A**: 可以直连,但仅供开发。生产环境必须用Runtime的原因:
- **安全**: Java后端不应直接暴露给前端
- **功能**: Threads、Observability、Middleware等高级功能需要Runtime
- **协议**: Runtime处理Agent发现、路由等协议细节

### Q2: Node.js层需要OpenAI Key吗?

**A**: **不需要!** 使用`ExperimentalEmptyAdapter`,所有LLM调用在Java后端完成。

### Q3: Java后端需要改动多少代码?

**A**: 
- **最小改动**: 添加1个Controller(约50行) + 3个工具类(约150行)
- **零改动**: 您现有的Agent逻辑、ChatClient、工具方法

### Q4: 如何处理会话持久化?

**A**: 两种方案:
1. **在Java端**: 使用Spring Session + Redis存储ChatMemory
2. **使用CopilotKit Threads**(Premium功能): Runtime自动处理

### Q5: 如何添加自定义工具?

**A**: 在Java端使用@Tool注解,AG-UI自动处理:

```java
@Tool(description = "您的工具描述")
public String yourTool(String param) {
    // 业务逻辑
    return result;
}
```

前端会自动显示工具调用过程!

### Q6: 支持流式输出吗?

**A**: 完全支持!Spring AI的`stream().content()`会被自动转换为AG-UI的`TEXT_MESSAGE_CONTENT`事件流。

### Q7: 如何集成现有的RAG系统?

**A**: 在Java端正常使用Spring AI的VectorStore,无需改动:

```java
ChatClient chatClient = builder
    .defaultAdvisors(new QuestionAnswerAdvisor(vectorStore))
    .build();
```

AG-UI层完全透明。

---

## 附录:手写协议层方案(可选)

如果您不想依赖社区SDK,可以手写协议层。核心代码已在[第一章Step 1.3](#13-实现步骤基于选项b手写方案)中提供。

### 完整文件清单

需要创建的文件:

1. **AgUiEventType.java** (约30行) - 事件类型枚举
2. **AgUiEventEmitter.java** (约100行) - SSE发射器
3. **AgUiService.java** (约80行) - 服务封装
4. **AgUiController.java** (约50行) - Controller端点

**总计: 约260行纯Java代码,零外部依赖。**

### 优势

- ✅ 零外部依赖
- ✅ 完全可控
- ✅ 易于调试
- ✅ 生产级稳定

### 扩展:支持工具调用事件

如果需要显示工具调用过程,在AgUiService中添加:

```java
// 监听Spring AI的ToolCall事件
chatClient
    .prompt()
    .user(userMessage)
    .advisors(advisor -> advisor
        .param("chatMemory", chatMemory)
        .param("toolCallbackProvider", (ToolCallCallback) toolCall -> {
            // 发送TOOL_CALL_START
            emitter.sendToolCallStart(toolCall.id(), toolCall.name());
            
            // 执行工具
            Object result = toolCall.execute();
            
            // 发送TOOL_CALL_RESULT
            emitter.sendToolCallResult(toolCall.id(), result);
            
            return result;
        })
    )
    .stream()
    .content();
```

---

## 总结

### 关键要点

1. **架构清晰**: 前端(React) → BFF(Node.js 35行) → 业务(Java)
2. **职责分离**: Node.js层零业务逻辑,仅作代理
3. **增量改造**: Java后端最小改动,现有逻辑不变
4. **协议轻量**: AG-UI本质是SSE + JSON,核心实现约150-260行

### 开发时间估算

| 阶段 | 预计时间 |
|------|----------|
| Java后端改造 | 2-4小时 |
| Node.js BFF搭建 | 30分钟 |
| Next.js前端集成 | 1-2小时 |
| 测试与调试 | 2-4小时 |
| **总计** | **1-2天** |

### 下一步

1. ✅ 按本指引实现基础版本
2. ✅ 测试核心对话流程
3. ✅ 逐步添加工具调用
4. ✅ 集成现有业务系统
5. ✅ 生产部署

**祝您的企业Agent前端项目成功!** 🚀

---

**文档版本**: v4.0  
**最后更新**: 2026-03-11  
**维护者**: 企业AI团队
