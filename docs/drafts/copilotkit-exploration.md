# CopilotKit & AG-UI Protocol 探索记忆

> 创建时间: 2026-05-31
> 对 `~/Documents/CopilotKit/CopilotKit/` 中的 CopilotKit 代码库 和 `~/Documents/ag-ui-protocol/ag-ui` 中的 AG-UI Protocol 代码库的探索记录。

---

## CopilotKit 是什么

**CopilotKit** 是一个用于构建**全栈 agentic 应用**、**Generative UI**和**聊天应用**的 SDK。

它的核心价值在于：**解决 AI Agent 与前端 UI 之间的实时交互问题**。

传统 AI 应用的问题：
- Agent 是"黑盒"，前端无法感知其内部状态
- Agent 决策过程是同步的，无法暂停等待人类确认
- Agent 产生的"结构化数据"（如表单、选项）难以直接渲染到 UI

CopilotKit 通过 **AG-UI 协议**（基于 SSE 的事件流）实现：
- 前端可以**实时看到** Agent 的推理过程（streaming）
- Agent 可以**主动暂停**，等待人类输入后再继续
- Agent 可以返回**可交互的 UI 组件**，而不只是文本

### 核心特性

| 特性 | 解决什么问题 | 如何实现 |
|------|-------------|---------|
| **Chat UI** | 前端不知道 Agent 在做什么 | 通过 `TEXT_MESSAGE_CHUNK` 事件流式渲染文本 |
| **工具调用** | Agent 需要调用前端/后端能力 | `TOOL_CALL_*` 事件触发前端 handler 或后端工具 |
| **后端工具渲染** | Agent 调用后端工具后，结果如何展示 | `TOOL_CALL_RESULT` 事件返回结果；`ACTIVITY_SNAPSHOT/DELTA` 事件用于渲染 Agent 驱动的动态 UI（A2UI） |
| **Generative UI** | Agent 动态决定要渲染什么组件 | 通过 `responseSchema` 定义数据结构，前端动态渲染 |
| **共享状态** | Agent 需要知道前端的状态 | `Context` 随每次请求发送给 Agent |
| **Human-in-the-loop** | Agent 决策需要人类确认 | `interrupt()` 暂停 Agent，发送 `on_interrupt` 自定义事件，前端 `useInterrupt`/`useHumanInTheLoop` 渲染交互 UI |

### 三层架构
```
Frontend (React/Angular/Vanilla) → Runtime (Express/Hono) → Agent (LangGraph/CrewAI/BuiltIn/Custom)
```
所有层通过 **AG-UI 协议**通信（基于 SSE 的事件流）

**数据流向（以用户对话为例）：**
1. 用户在前端 Chat UI 输入消息
2. 前端通过 GraphQL 发送到 Runtime（v1）或直接 POST JSON（v2）
3. Runtime 解析请求，路由到对应的 Agent
4. Agent 执行推理，通过 SSE 实时推送 AG-UI 事件
5. 前端根据事件类型渲染：文本流、工具调用卡片、选择器等
6. 如果 Agent 调用 `interrupt()`，发送 `on_interrupt` 自定义事件，`RUN_FINISHED` 仍正常发出
7. 前端渲染交互 UI，用户选择后，通过 `forwardedProps.command.resume` 恢复 Agent

---

### 典型场景：人类参与推理循环

**场景**：用户问"帮我预订下周的会议"，Agent 推理后发现需要用户确认时间选项。

**实现流程**：

```
用户: "帮我预订下周的会议"
  ↓
前端 → Runtime → Agent
  ↓
Agent 推理 → 调用 interrupt({ slots: [A, B, C, D], topic: "会议" })
  ↓
Agent 发送 on_interrupt 自定义事件（含 interrupt 数据）
  ↓
Agent 发送 RUN_FINISHED，前端检测到 pending interrupt
  ↓
前端渲染: 选择卡片（显示 A/B/C/D 四个选项）
  ↓
用户点击选项 B
  ↓
前端调用 resolve() → 发送 RunAgentInput { forwardedProps: { command: { resume: { chosen: "B" }, interruptEvent: {...} } } }
  ↓
Agent 恢复执行 → 继续完成预订
```

**后端代码示例**（LangGraph + TypeScript）：
```typescript
// 定义带 interrupt 的工具
const scheduleMeeting = tool(
  async ({ topic, attendee }) => {
    // interrupt() 暂停执行，等待前端响应
    const response = interrupt({
      topic,
      slots: candidateSlots(), // [A, B, C, D]
    });

    if (response.cancelled) return "用户取消";
    return `会议已预订: ${response.chosen_label}`;
  },
  { name: "schedule_meeting", schema: z.object({...}) }
);
```

**前端代码示例**（React）：
```tsx
// useInterrupt hook 监听 interrupt 事件
useInterrupt({
  agentId: "scheduler",
  render: ({ event, result, resolve }) => (
    <div>
      <p>请选择会议时间：</p>
      {event.value.slots.map(slot => (
        <button key={slot.iso} onClick={() => resolve(slot)}>
          {slot.label}
        </button>
      ))}
    </div>
  )
});
```

---

## 主要包结构 (@copilotkit/)

| 包名 | 用途 |
|------|------|
| `shared` | 通用工具、类型、常量 |
| `core` | `CopilotKitCore` - 前端核心 orchestrator |
| `react-core` | React 钩子 (`<CopilotKit>` provider) |
| `react-ui` | Chat UI 组件 (`CopilotChat`, `CopilotPopup`, `CopilotSidebar`, `CopilotModal`) |
| `react-textarea` | AI 辅助文本编辑组件 |
| `react-native` | React Native 支持 |
| `angular` | Angular 集成（npm scope: `@copilotkitnext/`） |
| `vue` | Vue 3 集成 |
| `runtime` | `CopilotRuntime` - 服务端运行时（含 `BuiltInAgent` - Vercel AI SDK 驱动的默认 Agent） |
| `runtime-client-gql` | urql GraphQL 客户端 |
| `voice` | 语音服务（转录、语音合成等） |
| `web-inspector` | Lit Web Component 调试控制台 |
| `sqlite-runner` | SQLite 持久化的 AgentRunner (CopilotKit2) |
| `agentcore-runner` | AWS Bedrock AgentCore 兼容的 AgentRunner (CopilotKit2) |
| `sdk-js` | LangGraph 集成辅助（中间件、状态注解、工具、header 传播） |
| `a2ui-renderer` | A2UI Generative UI 渲染器 |
| `demo-agents` | 演示用 Agent (私有包) |

> **CopilotKit2**：内部对新一代架构的称谓，核心变化包括：(1) v2 React hooks（`useRenderToolCall`、`useFrontendTool` 等）；(2) 持久化 AgentRunner（`sqlite-runner`、`agentcore-runner`）；(3) 简化的 REST 传输路径替代原有 GraphQL。所有 `@copilotkit/` 包均已升级支持 v2 API，v1 兼容 API 仍可用。

---
> **v1/v2 合并说明**：`@copilotkit/` 包的 v1/v2 代码已合并到同一个包中，不再有独立的 v1/v2 子目录。`react-core` 的 `useCopilotAction`（v1）和 `useFrontendTool`（v2）、`runtime` 的 GraphQL（v1）和 REST（v2）共存于同一包内，底层可以互操作。

## AG-UI 协议

### 定位与设计目标

AG-UI 是 CopilotKit 设计的**事件驱动协议**，填补了 AI Agent 与前端应用之间的通信空白：

| 协议 | 层级 | 目的 |
|------|------|------|
| **MCP** | 工具层 | 给 Agent 提供工具能力 |
| **A2A** | Agent 间通信 | Agent 之间互相调用 |
| **AG-UI** | 应用层 | Agent 与前端 UI 实时交互 |

**核心设计原则**：
- 基于 **SSE**（Server-Sent Events）实现实时推送
- 所有事件都是 **不可变的**（append-only），前端通过事件序列重建状态
- 支持 **中断恢复**（interrupt/resume），实现 Human-in-the-loop
- 事件类型覆盖完整的 Agent 生命周期

### 事件流生命周期

```
RUN_STARTED → (TEXT_MESSAGE_CHUNK | TOOL_CALL_START | STATE_SNAPSHOT | ACTIVITY_SNAPSHOT | REASONING_* | ...)+ → RUN_FINISHED
                                    ↑
                              可随时出现
```

当 Agent 调用 `interrupt()` 时，会先发送 `on_interrupt` 自定义事件（携带中断数据），随后正常发送 `RUN_FINISHED`。中断数据在自定义事件通道传递，而非嵌入 `RUN_FINISHED` 的 payload。前端 `useInterrupt` 在 `onRunFinalized` 时检测 pending interrupt 并：
1. 渲染交互 UI（选择卡片、确认对话框等）
2. 用户交互后，通过 `resolve()` → `forwardedProps.command.resume` 继续 Agent
3. 新请求携带 `forwardedProps: { command: { resume, interruptEvent } }`，Agent 收到后继续执行

### 事件类型 (共33种，含5种已废弃)

**生命周期事件 (5种):**
- `RUN_STARTED` / `RUN_FINISHED` / `RUN_ERROR`
- `STEP_STARTED` / `STEP_FINISHED`

**消息事件 (5种):**
- `TEXT_MESSAGE_START` / `TEXT_MESSAGE_CONTENT` / `TEXT_MESSAGE_END`
- `TEXT_MESSAGE_CHUNK`
- `MESSAGES_SNAPSHOT`

**工具调用事件 (5种):**
- `TOOL_CALL_START` / `TOOL_CALL_ARGS` / `TOOL_CALL_END`
- `TOOL_CALL_CHUNK`
- `TOOL_CALL_RESULT`

**状态事件 (2种):**
- `STATE_SNAPSHOT` / `STATE_DELTA`

**Activity 事件 (2种):**
- `ACTIVITY_SNAPSHOT` / `ACTIVITY_DELTA`

**推理事件 (7种):**
- `REASONING_START` / `REASONING_MESSAGE_START` / `REASONING_MESSAGE_CONTENT` / `REASONING_MESSAGE_END` / `REASONING_MESSAGE_CHUNK` / `REASONING_END`
- `REASONING_ENCRYPTED_VALUE`

**其他 (2种):**
- `RAW` / `CUSTOM`

**已废弃事件 (5种):**
- `THINKING_START` / `THINKING_END`
- `THINKING_TEXT_MESSAGE_START` / `THINKING_TEXT_MESSAGE_CONTENT` / `THINKING_TEXT_MESSAGE_END`

### AG-UI 包结构 (@ag-ui/)

| 包名 | 用途 |
|------|------|
| `core` | 核心事件类型、schema、capabilities |
| `client` | 客户端 SDK (`HttpAgent`, `AbstractAgent`) |
| `encoder` | 内容协商 (SSE/protobuf) |
| `proto` | Protocol Buffer 编解码 |

### 传输机制
1. **SSE** (默认) - JSON 编码的 HTTP 流
2. **Protocol Buffers** - 高性能二进制编码
3. **Content Negotiation** - 根据 `Accept` 头自动选择格式

### Capabilities（Agent 能力声明）

每个 Agent 通过 `AgentCapabilities` 声明自身支持的功能，前端根据 capabilities 决定如何与 Agent 交互：

```typescript
{
  tools:    { supported: true, clientProvided: true, parallelCalls?: true },
  transport: { streaming: true },
  reasoning: { supported: true, streaming: true },
  identity:  { name: "my-agent", type: "custom" },
}
```

- **tools**: Agent 是否支持工具调用（`clientProvided` 表示接受前端提供的工具列表）
- **transport**: 通信方式（`streaming` 表示支持 SSE 流式传输）
- **reasoning**: Agent 是否支持推理过程展示
- **identity**: Agent 的标识信息

`BuiltInAgent` 自动推断默认 capabilities，也支持按分类浅合并覆盖。

---

## 关键概念

### ProxiedAgent

前端对远程 Agent 的代理对象。实现了 `@ag-ui/client` 的 `AbstractAgent` 接口，但将调用翻译为 HTTP 请求到 Runtime，并通过 SSE 订阅事件流。

**创建时机**：前端初始化时，Runtime 返回可用 Agent 列表，前端自动为每个 Agent 创建 ProxiedAgent。

**核心方法**：
- `runAgent(input)` - 发起一次 Agent 运行
- `subscribe(subscriber)` - 订阅事件（`onMessagesChanged`, `onStateChanged`, `onRunInitialized` 等）
- `abortRun()` - 中止当前运行

### AgentRunner

Runtime 端的抽象类，负责管理 thread（对话线程）和 conversation 状态。

**内置实现**：
- `InMemoryAgentRunner` - 内存存储，进程重启后丢失
- `SqliteAgentRunner` - 持久化到 SQLite（CopilotKit2）
- `IntelligenceAgentRunner` - 通过 WebSocket (Phoenix Channels) 连接到 Copilot Cloud Intelligence 平台

**关键能力**：保存 Agent 执行状态，支持 `interrupt` 后的 `resume`。

### Tool Registration

CopilotKit 支持两类工具，注册方式和执行位置不同：

| 类型 | 注册位置 | 执行位置 | 典型用途 |
|------|---------|---------|---------|
| **前端工具** | `useFrontendTool()` / `useCopilotAction()` | 浏览器内 | 获取浏览器状态、操作 DOM |
| **后端工具** | Agent 定义中 | Runtime 服务器 | 访问数据库、调用外部 API |

**前端工具示例**（v1 API）：
```tsx
useCopilotAction({
  name: "getCurrentUrl",
  description: "Get the current browser URL",
  parameters: [],  // v1 使用 Parameter[] 格式
  handler: async () => {
    return window.location.href; // 在浏览器内执行
  }
});
```

**前端工具示例**（v2 API）：
```tsx
useFrontendTool({
  name: "getCurrentUrl",
  description: "Get the current browser URL",
  parameters: z.object({}),  // v2 使用 Zod schema
  handler: async () => {
    return window.location.href; // 在浏览器内执行
  }
});
```

### Context（上下文）

前端向后端传递应用状态的机制。用于让 Agent 知道当前 UI 状态（如当前页面、选中的数据等）。

```tsx
useAgentContext({
  description: "currentDocument",
  value: { id: "123", title: "My Doc" }
});
```

这些 Context 会随每次 `RunAgentInput` 发送给 Agent。

### Multi-Agent

CopilotKit 支持在同一个 Runtime 中运行多个 Agent，每个 Agent 有：
- 独立的 `agentId`
- 独立的 thread 和状态
- 独立的工具作用域

前端通过 `useAgent({ agentId: "specificAgent" })` 选择与哪个 Agent 对话。

### Middleware

Runtime 的请求处理钩子，用于：
- `beforeRequestMiddleware` - 请求到达前处理（如认证、鉴权）
- `afterRequestMiddleware` - 响应发送前处理（如日志、转换）

```typescript
const runtime = new CopilotRuntime({
  beforeRequestMiddleware: async ({ request, path, runtime }) => {
    // 验证 token
    // 添加 header
    return request;
  }
});
```

### Debug Mode

CopilotKit 提供内置的调试模式，用于开发阶段排查 AG-UI 事件流：

**启用方式**：
- **Runtime**（服务端）：`new CopilotRuntime({ debug: true })` — 使用 Pino 结构化日志输出所有 AG-UI 事件
- **Client**（React）：`<CopilotKit debug={true}>` — 配置底层 AG-UI transport 层的调试输出

**细粒度配置**：
```typescript
debug: {
  events: true,    // 记录每条发出/接收的事件 (默认: true)
  lifecycle: true,  // 记录请求/运行生命周期 (默认: true)
  verbose: false,   // 完整 payload vs 摘要 (对象模式下默认 false，布尔模式下默认 true)
}
```

`DebugConfig` 类型和 `resolveDebugConfig()` 位于 `@copilotkit/shared`。Runtime 和 Client 的调试开关相互独立。

---

## 请求生命周期

### 普通对话流程

```
1. 初始化阶段
   前端 CopilotKitProvider
   → GET /info (REST, v2) 或通过 GraphQL availableAgents 查询 (v1)
   → Runtime 返回可用 Agent 列表及 capabilities
   → 前端为每个 Agent 创建 ProxiedAgent

2. 用户发送消息
   用户在 Chat UI 输入 "帮我预订会议室"
   → 前端调用 agent.runAgent({ messages: [...], context: [...] })
   → ProxiedAgent 发起 HTTP POST 到 Runtime

3. Runtime 处理
   → 执行 beforeRequestMiddleware (认证/日志)
   → 解析请求，路由到对应 Agent
   → 创建/获取 Thread（对话线程）
   → 调用 AgentRunner 执行

4. Agent 执行 + 事件流
   Agent 开始推理
   → 发送 RUN_STARTED { runId: "xxx" }
   → 发送 TEXT_MESSAGE_CHUNK (流式文本)
   → (如果需要) 发送 TOOL_CALL_START / TOOL_CALL_ARGS
   → 发送 RUN_FINISHED

5. 前端处理
   → Core 接收 SSE 事件
   → 更新 messages store
   → 触发 React 重新渲染
   → 显示流式文本 / 工具调用卡片
```

### Interrupt 流程（Human-in-the-loop）

当 Agent 调用 `interrupt()` 时，流程会"暂停"并等待用户输入：

```
1-4. 同上，但最后 Agent 调用 interrupt({...})
   → Agent 发送 on_interrupt 自定义事件（含 interrupt 数据）
   → Agent 发送 RUN_FINISHED
   → Runtime 保存 Thread 状态（包括 interrupt 位置）

5. 前端收到 interrupt
   → useInterrupt hook 在 onCustomEvent 中检测到 interrupt 事件
   → RUN_FINISHED 触发后，onRunFinalized 中设置 pending interrupt
   → 渲染对应的 React 组件（如选择卡片）
   → UI 处于"等待用户输入"状态

6. 用户交互
   用户点击选项 B
   → 前端调用 resolve({ chosen: "B" })
   → ProxiedAgent 发起新的 runAgent({ forwardedProps: { command: { resume: { chosen: "B" }, interruptEvent: {...} } } })

7. Agent 恢复
   → Runtime 找到之前的 Thread
   → 根据 forwardedProps.command.resume 恢复 Agent 执行
   → Agent 继续推理，完成预订
   → 发送最终 RUN_FINISHED
```

### RunAgentInput 结构

```typescript
interface RunAgentInput {
  threadId: string;        // 线程 ID（用于恢复对话上下文）
  runId: string;           // 新的运行 ID
  parentRunId?: string;    // 父运行 ID（用于跟踪）
  state: any;              // 应用状态（Agent 可读写）
  messages: Message[];      // 对话历史
  tools: Tool[];           // 可用工具列表
  context: Context[];        // 前端上下文
  forwardedProps?: any;     // 自定义属性（含 command.resume 用于中断恢复）
}
```

**中断恢复机制**：resume 数据通过 `forwardedProps.command` 传递：
```typescript
// 前端 resolve() 时发送
forwardedProps: {
  command: {
    resume: <用户响应数据>,           // 用户通过 resolve() 提供的值，最终会作为 interrupt() 的返回值
    interruptEvent: <原始 interrupt 值>,  // 用于后端恢复上下文
  },
}
```

> 注意：`RunAgentInput` **不直接包含 `resume` 字段**，中断恢复通过 `forwardedProps.command` 透传。`forwardedProps` 是一个 opaque 通道，AG-UI 协议层不做解析，直接传递给 Agent Runtime。

---

## 相关项目

- **AG-UI Protocol**: https://github.com/ag-ui-org/ag-ui
  - 官方协议实现，CopilotKit 是主要消费者/实现者
  - 支持多种 Agent 框架集成：
    - **Runtime 内置适配器**：LangGraph（`@copilotkit/runtime/langgraph`）
    - **外部 `@ag-ui/*` 包**：CrewAI (`@ag-ui/crewai`)、Mastra (`@ag-ui/mastra`)、LlamaIndex (`@ag-ui/llamaindex`)、Agno (`@ag-ui/agno`)、A2A (`@ag-ui/a2a`)
    - **通用 `HttpAgent`**（`@ag-ui/client`）：PydanticAI、Google ADK、AWS Strands、Microsoft Agent Framework、AG2 等
  - Python SDK: `ag-ui-protocol`
