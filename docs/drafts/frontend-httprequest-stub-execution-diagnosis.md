# 前端 httpRequest 工具被后端 stub 执行问题诊断报告

**日期**: 2026-06-12
**状态**: 已定位根因，等待用户授权修复

## 问题现象

E2E 测试（repro-final-verify.mjs）观察到的现象：

1. ✅ 页面不再崩溃（修复了之前 React "Objects are not valid as a React child" 错误）
2. ✅ 前端 `useCopilotAction` 注册的 `httpRequest` 工具正常注册到 LLM
3. ✅ 前端实际发起了 6 次 GET `/api/products` 请求（携带 user access token）
4. ❌ **LLM 回复仍然是 "The API call returned an empty array"** —— LLM 没有看到前端实际拿到的商品列表（iPhone 15、宠物等）
5. ❌ 加购物车时未弹出确认按钮，调用 `/api/products/cart` 返回 404

后端日志中的关键证据：

```
o.s.a.m.tool.DefaultToolCallingManager : Executing tool call: httpRequest
[Merged 3 toolCallbacks for LLM (frontend input.tools + backend this.toolCallbacks): httpRequest, loadSkill, readSkillReference
```

**核心问题**：后端 Spring AI 的 `DefaultToolCallingManager` 把前端的 `httpRequest` 工具当成可执行函数 stub 执行了，返回空结果给 LLM。LLM 看到的是 stub 的空结果，**不是前端实际 HTTP 请求的响应**。

## 根因分析

### 数据流（错误路径）

```
┌────────────────┐  input.tools=[httpRequest]   ┌──────────────────┐
│ CopilotKit FE  │ ───────────────────────────▶ │  SpringAIAgent   │
│ (useCopilot    │                              │  .run(input)     │
│  Action 注册)  │                              │  line 1105-1172  │
└────────────────┘                              └─────────┬────────┘
        │                                                │
        │                                                ▼
        │                                ┌──────────────────────────────┐
        │                                │ mergedToolCallbacks 包含     │
        │                                │   - httpRequest (来自 FE)    │
        │                                │   - loadSkill (来自 BE)      │
        │                                │   - readSkillReference       │
        │                                └────────┬─────────────────────┘
        │                                         │
        │                                         ▼
        │                            ┌────────────────────────────┐
        │                            │  chatRequest.toolCallbacks │
        │                            │  → Spring AI ChatClient    │
        │                            └────────┬───────────────────┘
        │                                     │
        │                                     ▼
        │                          ┌──────────────────────────┐
        │                          │ Spring AI 请求 LLM       │
        │                          │ (tools=[httpRequest,    │
        │                          │   loadSkill, ...])      │
        │                          └────┬─────────────────────┘
        │                               │
        │                               ▼
        │                  ┌──────────────────────────────┐
        │                  │ LLM 返回 tool_call:          │
        │                  │   {name: "httpRequest", ...} │
        │                  └────┬─────────────────────────┘
        │                       │
        │                       ▼
        │            ┌─────────────────────────────────────┐
        │            │ DefaultToolCallingManager           │
        │            │ .executeToolCalls()                 │
        │            │                                     │
        │            │ httpRequest 工具被识别为已注册      │
        │            │ （在 mergedToolCallbacks 里）       │
        │            │                                     │
        │            │ 调用 httpRequest.call(args)         │
        │            │ → 返回 stub 结果（空对象/失败）     │
        │            └────┬────────────────────────────────┘
        │                 │
        │                 ▼
        │        ┌──────────────────────────────┐
        │        │ ToolResponseMessage:        │
        │        │   result = "" (stub)        │
        │        └────┬─────────────────────────┘
        │             │
        │             ▼
        │    ┌──────────────────────────────────┐
        │    │ Spring AI 再次请求 LLM           │
        │    │ 注入 tool result = ""            │
        │    └────┬─────────────────────────────┘
        │         │
        │         ▼
        │  ┌────────────────────────────────┐
        │  │ LLM 看到 "httpRequest 返回     │   ◀── LLM 据此说
        │  │ 空数组" → 告诉用户没商品       │       "API returned
        │  └────────────────────────────────┘       empty array"
        │
        │
        │  ┌────────────────────────────────────────────┐
        │  │ 同时：CopilotKit 前端用 useCopilotAction   │
        │  │ 拦截到 tool_call，弹确认框 / 直接发请求    │
        │  │ ── 实际拿到了 iPhone 15 商品列表 ──       │
        │  │ ── 但这个结果没有回传给 LLM ──            │
        │  └────────────────────────────────────────────┘
        ▼
   用户的 UI 看到：
   - 前端确认弹窗（write op）正常  ✓
   - AI 回复内容却是错的         ✗
```

### 关键代码位置

`src/main/java/com/agui/spring/ai/SpringAIAgent.java` 第 1105-1172 行：

```java
// 第 1113-1129 行：前端工具被无条件转成 Spring AI ToolCallback
if (!input.tools().isEmpty()) {
    for (var tool : input.tools()) {
        String toolName = tool.name();
        if (backendToolNames.contains(toolName)) {
            skippedDuplicateNames.add(toolName);
            continue;  // 只跳过"和后端同名"的工具
        }
        mergedToolCallbacks.add(
            this.toolMapper.toSpringTool(tool, messageId, deferredEvents::add)
            //     ▲
            //     └─ 这里把前端的 httpRequest 包装成 Spring AI 内部可执行的 ToolCallback
            //        然后 DefaultToolCallingManager 看到它已注册就会 stub 执行
        );
    }
}
```

`this.toolMapper.toSpringTool(...)` 返回的是一个**立即返回空 stub 结果**的 ToolCallback（这是 Spring AI 默认实现），目的是把 AG-UI 事件流出去后让前端来执行。但实际上**当 `internalToolExecutionEnabled=true` 时**，DefaultToolCallingManager 真的会调用 `toolCallback.call(args)` —— 然后得到 stub 返回值。

### 期望行为

**理想数据流（正确路径）**：

```
LLM 返回 tool_call: httpRequest
  ↓
后端识别出这是前端工具（不在 toolCallbacksForExecution 里）
  ↓
发射 TOOL_CALL_START / ARGS / END 事件给前端
  ↓
**不调用 DefaultToolCallingManager 执行**
**不发送 stub TOOL_CALL_RESULT 事件**
  ↓
结束当前 run（RUN_FINISHED）
  ↓
前端 CopilotKit 拦截到 tool_call，弹确认 / 实际执行 HTTP
  ↓
前端在下一轮对话时把 tool result 传回后端
  ↓
LLM 看到真实结果，继续生成回答
```

## 关键代码位置 2

`src/main/java/com/agui/spring/ai/SpringAIAgent.java` 第 944-1004 行 `executeToolCallsAndReRun()` 方法**已经有部分正确逻辑**：

```java
// 第 952-973 行：toolCallbacksForExecution 查不到的工具，跳过执行
ToolCallback toolCallback = this.toolCallbacksForExecution.stream()
    .filter(cb -> toolName.equals(cb.getToolDefinition().name()))
    .findFirst()
    .orElse(null);

if (toolCallback != null) {
    // 后端注册的工具 → 真正执行
    result = toolCallback.call(toolArguments);
} else {
    // 工具不在本地注册（如 httpRequest 由前端 useCopilotAction 处理）
    log.info("工具 {} 未在后端注册，跳过执行（由前端处理）", toolName);
    allToolsFound = false;
    continue;
}
```

**但是这个逻辑只对"非内部执行"路径有效**。当 `internalToolExecutionEnabled=true`（`AgUiConfig.java` 当前的设置），`DefaultToolCallingManager` 会在 `mergedToolCallbacks` 找到前端 `httpRequest` 的 stub 包装器并直接执行它，永远到不了 `executeToolCallsAndReRun()`。

## 提议的修复方案（需用户授权后实施）

### 方案 A（推荐）：在 merge 时不把前端工具加到 mergedToolCallbacks

**修改位置**：`SpringAIAgent.java` 第 1113-1129 行

**修改要点**：
- 当 `internalToolExecutionEnabled=true` 时，前端的 `input.tools()` **不再**被 `toolMapper.toSpringTool()` 包装成可执行的 Spring AI ToolCallback
- 而是通过另一种方式（如设置 `ToolCallingChatOptions` 的 `toolNames`，或使用 Spring AI 的 "tool name only" 模式）告诉 LLM 工具存在
- 这样 `DefaultToolCallingManager` 找不到这个工具的 callback，会触发 "no tool callback for name" 异常 → 走 fallback 路径到 `executeToolCallsAndReRun()` → 那里正确处理为"前端工具"

**风险评估**：
- ⚠️ TTFT 可能略微退化（Spring AI 处理 "tool not found" 比直接执行多一次反射/异常路径）
- 需要实测 minTTFT 退化 < 50ms 才符合约束

### 方案 B（更保守）：保留 mergedToolCallbacks 但让 stub 返回特定标记

**修改要点**：
- `toolMapper.toSpringTool()` 返回的 stub callback 在被 Spring AI 调用时，返回一个明确的标记字符串，如 `__FRONTEND_TOOL_PENDING__`
- 然后在 `executeToolCallsAndReRun()` 里识别这个标记 → 不使用该结果，跳过 → 等前端回调

**风险评估**：
- ✅ TTFT 不退化（Spring AI 仍然一次执行）
- ⚠️ LLM 仍然会"看到"一个 tool result（虽然是个标记），可能干扰它的判断
- 需要修改 `AgUiFunctionToolCallback` 的包装层

### 方案 C（破坏性，备选）：让前端工具永远不在 chatRequest.toolCallbacks 中

**修改要点**：
- 当 `internalToolExecutionEnabled=true` 时，**完全**不把前端 `input.tools()` 传给 `chatRequest.toolCallbacks()`
- 仅在 SSE 流里通过 `TOOL_CALL_*` 事件告知 LLM 工具存在（用伪 events 引导 LLM 走前端工具）
- 这样 LLM 看不到前端工具的 schema，可能直接拒绝调用

**风险评估**：
- ❌ LLM 不知道前端有 httpRequest 工具 → 不会主动调用
- 需要在 system prompt 显式提示 "如果有 httpRequest 工具可用请先 loadSkill 查看"
- 实际不可行，放弃

## 备选诊断：cart 返回 404

`/api/products/cart` 返回 404 可能是真实后端缺失该 endpoint。需要在 ProductController.java 验证：
- GET /api/products/cart - 购物车列表
- POST /api/products/cart - 添加到购物车
- POST /api/products/checkout - 结算

如果 endpoint 确实存在，404 可能是 Spring Security 拦截了未带 token 的请求。

## 待用户决定

1. 是否授权修复 `SpringAIAgent.java`？
2. 如果授权，选择方案 A、B、还是 C？
3. 是否同时检查 `/api/products/cart` endpoint 的实际可用性？
4. 修复后是否需要写一个 E2E 测试断言"前端 6 次 GET 后，LLM 回复必须包含 'iPhone 15' 等商品名"？
