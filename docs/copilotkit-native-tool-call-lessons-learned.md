# CopilotKit 原生 httpRequest 工具调用 — 经验教训总结

> **创建时间**: 2026-06-15
> **文档状态**: 历史经验记录
> **目的**: 总结实现"CopilotKit 原生 `useCopilotAction` 工具调用"过程中踩过的坑和学到的教训
> **适用范围**: Spring AI + CopilotKit + AG-UI 协议的前后端混合工具调用场景
> **阅读对象**: 未来的开发者（特别是需要维护或扩展此功能的开发者）
>
> 本文记录迁移过程，部分段落使用旧的 `useCopilotAction` 和“JWT”命名。
> 当前实现使用 CopilotKit v2、`useHumanInTheLoop` 和 Demo Base64 token；
> 当前架构请以 [架构说明](ARCHITECTURE.md) 为准。

---

## 一、问题背景

### 1.1 我们要做什么？

实现一个"企业助手"功能：用户在前端 CopilotKit 聊天窗口提问，LLM 决定调用哪些工具（如 `loadSkill`、`httpRequest`），后端和前端协同执行，最终返回结果给用户。

**关键需求**：
- `loadSkill`、`readSkillReference` 等工具在**后端**执行（访问本地技能文件）
- `httpRequest` 工具在**前端**执行（需要用户的 JWT token、需要弹确认框）

### 1.2 为什么这么难？

1. **CopilotKit 和 Spring AI 是两套独立的框架**，它们对"工具调用"的理解不同
2. **AG-UI 协议是桥梁**，但它的文档和示例不够完善
3. **Spring AI 1.1.2 有多个 bug**（JSON 参数反序列化、工具执行逻辑等）
4. **推理模型（MiniMax-M3、DeepSeek-R1）行为不可预测**（输出 XML 标签、不遵守 system prompt）

---

## 二、核心教训（按重要性排序）

### 教训 1：同名工具是万恶之源

**问题**：前端和后端都注册了名为 `httpRequest` 的工具，LLM 看到两个同名工具会**静默 hanging**（不报错、不调用、不回复）。

**根因**：
- Spring AI 的 `ToolCallingChatOptions` 会合并所有工具
- LLM 收到两个同名 `httpRequest` 的 schema，无法决定用哪个
- CopilotKit v1 的 `renderAndWaitForResponse` 是 deprecated API，respond() 结果不回传

**解决方案**：
- **后端只注册 `loadSkill` + `readSkillReference`**（不含 HTTP 工具）
- **前端 `useCopilotAction` 注册唯一的 `httpRequest`**
- 在 `SpringAIAgent.java` 合并工具时，**按工具名去重保留后端版本**

**代码位置**：
```java
// SpringAIAgent.java 第 1259-1264 行
if (backendToolNames.contains(toolName)) {
    skippedDuplicateNames.add(toolName);
    continue;  // 跳过与后端同名的前端工具
}
```

**教训**：永远不要让前端和后端注册同名工具！

---

### 教训 2：Spring AI 的 `internalToolExecutionEnabled=false` 是关键

**问题**：当 `internalToolExecutionEnabled=true`（默认）时，Spring AI 的 `DefaultToolCallingManager` 会执行**所有**注册的工具，包括前端工具的 stub。stub 返回空结果，LLM 看到空结果就说"API returned empty array"。

**根因**：
- `toolMapper.toSpringTool()` 返回的是一个**立即返回空 stub 结果**的 ToolCallback
- `DefaultToolCallingManager` 找到这个 callback 就直接执行
- 前端实际拿到的真实数据（如 5 个商品）**永远到不了 LLM**

**解决方案**：
```java
// AgUiConfig.java
return SpringAIAgent.builder()
    .internalToolExecutionEnabled(false)  // 关键！
    .toolCallbacks(toolCallbacks)         // 后端工具
    .toolCallbacksForExecution(toolCallbacks)  // 用于手动执行
    .build();
```

**工具执行流程**（`internalToolExecutionEnabled=false` 时）：
1. LLM 返回 tool_call（如 `httpRequest`）
2. Spring AI **不自己执行**，透传原始 tool_calls 到 subscriber
3. `SpringAIAgent.executeToolCallsAndReRun()` 手动处理：
   - 后端工具（`loadSkill`）→ 执行并返回结果
   - 前端工具（`httpRequest`）→ 跳过，发射 `RUN_FINISHED`，等待前端 respond()
4. 前端 `useCopilotAction` 拦截 tool_call → 执行 HTTP → respond(result)
5. CopilotKit 触发下一轮 `/api/copilotkit`，后端收到 tool_result

**教训**：当有"前端工具"时，必须关闭 Spring AI 的内部工具执行！

---

### 教训 3：SSE 生命周期必须正确关闭

**问题**：前端永远卡在 "inProgress" 状态，不进入 "executing"，respond() 永远不被调用。

**根因**：
- `SpringAIAgent.executeToolCallsAndReRun()` 检测到前端工具时，只发射 `RUN_FINISHED` 但**未调用 `subscriber.onRunFinalized()`**
- SSE 流永远不关闭 → BFF 的 `HttpAgent` 永远等待 → 前端的 `agent.runAgent()` 永远不返回

**修复**：在**所有**退出路径都调用 `subscriber.onRunFinalized()`：

```java
// 1. 前端工具路径
if (!allToolsFound) {
    this.emitEvent(runFinishedEvent(...), subscriber);
    subscriber.onRunFinalized(new AgentSubscriberParams(...));  // 必须！
    return true;
}

// 2. 错误处理器
err -> {
    this.emitEvent(runErrorEvent(err.getMessage()), subscriber);
    subscriber.onRunFinalized(new AgentSubscriberParams(...));  // 必须！
}

// 3. 外层 catch 块
catch (AGUIException e) {
    this.emitEvent(runErrorEvent(e.getMessage()), subscriber);
    subscriber.onRunFinalized(new AgentSubscriberParams(...));  // 必须！
}
```

**教训**：SSE 流的生命周期管理是最容易忽略的 bug 来源！

---

### 教训 4：Spring AI 1.1.2 的 JSON 参数反序列化 bug

**问题**：`MethodToolCallback` 在反序列化 JSON 参数时，即使使用了 `-parameters` 编译选项，也无法正确将 JSON 键映射到方法参数。

**症状**：`loadSkill("search-products")` 被调用时，`skillName` 参数为 null。

**解决方案**：自定义 `JsonArgToolCallback` 包装器：

```java
public class JsonArgToolCallback implements ToolCallback {
    @Override
    public String call(String toolInput) {
        JsonNode rootNode = objectMapper.readTree(toolInput);
        Object[] args = new Object[parameterNames.length];
        for (int i = 0; i < parameterNames.length; i++) {
            JsonNode paramNode = rootNode.get(parameterNames[i]);
            args[i] = (paramNode != null && !paramNode.isNull()) ? paramNode.asText() : null;
        }
        return (String) targetMethod.invoke(targetObject, args);
    }
}
```

**教训**：Spring AI 的 bug 不少，必要时需要自己写 wrapper！

---

### 教训 5：推理模型会泄漏 XML/JSX 标签

**问题**：MiniMax-M3、DeepSeek-R1 等推理模型会输出 ``、`<parameter>`、`<invoke>` 等标签，React 会把这些标签当作未知 HTML/JSX 标签处理，触发 "The tag X is unrecognized in this browser" 警告。

**解决方案**：`StreamingTagFilter`（跨 chunk 流式过滤器）：

```java
private static final class StreamingTagFilter {
    private final StringBuilder buffer = new StringBuilder();
    private boolean inThinkBlock = false;
    private String currentTagName = null;

    public String process(String delta) {
        // 跨 chunk 状态机：识别 <parameter>/<invoke>/<tool_call> 等标签
        // 在标签内时不输出，标签外时正常输出
    }
}
```

**注意**：``/`<thinking>` 标签**不需要过滤**，CopilotKit UI 会正确渲染为"思考过程"折叠区。

**教训**：推理模型的输出不可预测，必须有防御性过滤！

---

### 教训 6：maxToolCalls 防御无限循环

**问题**：推理模型（如 MiniMax-M3）可能在单次 response 中发出多个完全相同的 tool_call，导致无限循环。

**解决方案**：

```java
// SpringAIAgent.java
public static final int DEFAULT_MAX_TOOL_CALLS = 5;

// 在 onEvent() 中检查
if (currentCount > this.maxToolCalls) {
    forceStopped[0] = true;
    // 发射停止提示
}
```

**教训**：对推理模型必须有硬性限制！

---

### 教训 7：CopilotKit v2 是完全平行的 API 栈

**问题**：尝试从 v1 的 `useCopilotAction.renderAndWaitForResponse` 迁移到 v2 的 `useHumanInTheLoop`，结果导致**聊天完全瘫痪**（LLM 从未响应）。

**根因**：
- v2 的 `useHumanInTheLoop` 调用 `useFrontendTool`
- `useFrontendTool` 走 v2 专属的 context
- 当前 `app/page.tsx` 用的是 v1 `CopilotKit` provider
- v2 工具注册到 v2 context，v1 provider 看不到 → LLM 不知道有 `httpRequest` 工具

**v2 迁移范围**（远超预期）：
1. `CopilotKit` provider → `CopilotKitProvider` (v2)
2. `CopilotPopup` → v2 版本
3. 所有 props 适配
4. BFF 路由可能需要适配
5. `useCopilotAction` → `useHumanInTheLoop`

**教训**：v1 → v2 不是换个 hook，是整体重构！除非必要，不要轻易迁移！

---

### 教训 8：ToolCallArgsEvent 必须收集到 deferredEvents

**问题**：`SpringAIAgent.onEvent()` 中只把 `ToolCallStartEvent` 添加到 `deferredEvents`，漏掉了 `ToolCallArgsEvent`。导致 `executeToolCallsAndReRun()` 收集不到工具参数，`loadSkill` 被调用时参数为空。

**修复**：
```java
// SpringAIAgent.java 第 585-587 行
deferredEvents.add(toolCallStartEvent(messageId, toolCall.name(), toolCallId));
deferredEvents.add(toolCallArgsEvent(toolCall.arguments(), toolCallId));  // 必须！
```

**教训**：事件收集必须完整，漏掉一个就会导致下游逻辑失败！

---

### 教训 9：错误消息要包含可用选项

**问题**：LLM 经常编造技能名（如 `getAllProducts`、`get_all_products`），而不是使用正确的 `search-products`。

**解决方案**：在错误消息中显示可用技能列表：

```java
// SkillCoreTools.java
.orElse("✗ 错误：技能 `" + skillName + "` 不存在。可用技能：" +
        registry.all().keySet().stream().sorted().collect(Collectors.joining(", ")) +
        "\n请使用上述技能名称之一重新调用 loadSkill。");
```

**效果**：LLM 看到错误消息后会自我纠正，使用正确的技能名。

**教训**：错误消息是 LLM 学习的重要来源！

---

### 教训 10：Tomcat + SSE + ResponseEntity = NPE

**问题**：Tomcat 10.1.34 在 `ResponseEntity<SseEmitter>` 包装返回 + undici 长 SSE 连接时，会抛 `MimeHeaders.setValue NullPointerException`，导致 undici 收到 `bytesRead=0`。

**解决方案**：直接返回 `SseEmitter`，用 `HttpServletResponse` 注入 header：

```java
@PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter run(@RequestBody AgUiParameters params,
                      @RequestHeader(value = "Authorization", required = false) String authHeader,
                      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-cache");
    response.setHeader("X-Accel-Buffering", "no");
    response.setHeader("Connection", "keep-alive");
    return agUiService.runAgent(enterpriseAgent, params);  // 不要包 ResponseEntity
}
```

**教训**：SSE 端点不要用 `ResponseEntity` 包装！

---

## 三、架构设计原则

### 3.1 工具分配原则

| 工具 | 执行位置 | 理由 |
|------|---------|------|
| `loadSkill` | 后端 | 访问本地技能文件 |
| `readSkillReference` | 后端 | 访问本地参考文件 |
| `httpRequest` | 前端 | 需要 JWT token、需要确认框 |

**核心原则**：**一个工具只能在一个地方注册**，不能前后端都注册同名工具！

### 3.2 数据流原则

```
LLM 返回 tool_call
    ↓
后端识别工具类型
    ↓
┌─────────────────────────────────────┐
│ 后端工具？                          │
│   → 执行并返回结果                  │
│   → 保存到 ChatMemory               │
│   → re-run 让 LLM 看到结果          │
└─────────────────────────────────────┘
┌─────────────────────────────────────┐
│ 前端工具？                          │
│   → 跳过执行                        │
│   → 发射 RUN_FINISHED               │
│   → 等待前端 respond()              │
│   → 前端触发下一轮 /api/copilotkit  │
└─────────────────────────────────────┘
```

### 3.3 状态机原则

前端 `useHumanInTheLoop` 的三种状态：

1. **inProgress**：LLM 正在准备参数（显示"准备调用 httpRequest..."）
2. **executing**：handler 处于等待 resolve（显示"正在执行 GET /api/products..."）
3. **complete**：result 已回填（显示"✓ httpRequest 完成 (200)"）

**关键**：`respond` 函数只在 `status === "executing"` 时可用！

---

## 四、调试技巧

### 4.1 后端日志关键点

```bash
# 查看工具注册
grep "注册.*个工具 schema" /tmp/backend.log

# 查看工具执行
grep "执行工具:" /tmp/backend.log

# 查看前端工具跳过
grep "未在后端注册，跳过执行" /tmp/backend.log

# 查看 SSE 生命周期
grep "onRunFinalized" /tmp/backend.log
```

### 4.2 前端日志关键点

```javascript
// 在 useHttpRequestTool.tsx 添加日志
console.log('[HttpRequestRender]', { status: props.status, args: props.args });
```

### 4.3 Playwright E2E 测试

```javascript
// 监听网络请求
page.on('request', request => {
    if (request.url().includes('/api/copilotkit')) {
        console.log('CopilotKit request:', request.url());
    }
});

// 监听控制台错误
page.on('pageerror', error => {
    console.error('Page error:', error.message);
});

// 监听控制台日志
page.on('console', msg => {
    if (msg.type() === 'error') {
        console.error('Console error:', msg.text());
    }
});
```

### 4.4 curl 测试 AG-UI 协议

```bash
curl -N -X POST http://localhost:8080/api/agui \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer dXNlcjE6cGFzc3dvcmQx" \
  -d '{
    "threadId": "test-thread-1",
    "runId": "test-run-1",
    "messages": [{"role": "user", "content": "有什么商品可以买？"}],
    "tools": [],
    "context": []
  }'
```

---

## 五、常见问题 FAQ

### Q1: LLM 收到空的 tool_result 怎么办？

**A**: 检查是否关闭了 `internalToolExecutionEnabled`。如果开着，Spring AI 会执行 stub 并返回空结果。

### Q2: 前端永远卡在 "inProgress" 怎么办？

**A**: 检查是否在所有退出路径都调用了 `subscriber.onRunFinalized()`。SSE 流不关闭会导致前端永远等待。

### Q3: LLM 编造工具名怎么办？

**A**: 在错误消息中包含可用工具列表，让 LLM 自我纠正。

### Q4: 推理模型输出 XML 标签导致 React 报错怎么办？

**A**: 使用 `StreamingTagFilter` 过滤 `<parameter>`、`<invoke>`、`<tool_call>` 等标签。注意 `` 标签不需要过滤，CopilotKit 会正确渲染。

### Q5: Spring AI 的 JSON 参数反序列化失败怎么办？

**A**: 自定义 `JsonArgToolCallback` 包装器，手动解析 JSON 参数。

### Q6: v2 迁移后 LLM 不响应怎么办？

**A**: v2 是完全平行的 API 栈，需要同时迁移 provider、UI 组件、hook 体系。除非必要，不要轻易迁移。

---

## 六、关键文件清单

| 文件 | 作用 |
|------|------|
| `src/main/java/com/agui/spring/ai/SpringAIAgent.java` | AG-UI 核心桥梁，包含工具执行、SSE 生命周期、StreamingTagFilter |
| `src/main/java/com/example/demo/config/AgUiConfig.java` | AG-UI 配置，设置 internalToolExecutionEnabled=false |
| `src/main/java/com/example/demo/agent/SkillCoreTools.java` | 后端工具（loadSkill + readSkillReference） |
| `src/main/java/com/example/demo/agent/JsonArgToolCallback.java` | 修复 Spring AI JSON 参数反序列化 bug |
| `src/main/java/com/agui/core/event/RunErrorEvent.java` | 修复 RUN_ERROR 事件缺少 message 字段 |
| `src/main/java/com/example/demo/controller/AgUiController.java` | 修复 Tomcat SSE NPE |
| `frontend/hooks/useHttpRequestTool.tsx` | 前端 httpRequest 工具注册 |
| `frontend/app/api/copilotkit/route.ts` | BFF 路由，透传 AG-UI 事件 |

---

## 七、时间线（关键节点）

| 日期 | 事件 | 状态 |
|------|------|------|
| 2026-06-11 | CopilotKit 原生工具调用重构初版 | ✅ |
| 2026-06-12 | 发现同名工具冲突导致 LLM hanging | 🔧 修复 |
| 2026-06-12 | 发现前端工具被后端 stub 执行 | 🔧 诊断 |
| 2026-06-13 | 实现 internalToolExecutionEnabled=false 路径 | ✅ |
| 2026-06-13 | 发现 respond() 不工作（v1 deprecated API） | ❌ |
| 2026-06-13 | 尝试 v2 迁移（REGRESSION） | ❌ 回退 |
| 2026-06-14 | 修复 SSE 生命周期 bug | ✅ |
| 2026-06-14 | 修复 RUN_ERROR 事件兼容性 | ✅ |
| 2026-06-14 | 修复 Tomcat SSE NPE | ✅ |
| 2026-06-15 | 修复 Spring AI JSON 参数反序列化 bug | ✅ |
| 2026-06-15 | 修复 ToolCallArgsEvent 收集问题 | ✅ |
| 2026-06-15 | **全流程 E2E 测试通过** | ✅ |

---

## 八、总结

实现"CopilotKit 原生 httpRequest 工具调用"是一个**系统性工程问题**，涉及：
- 前后端工具注册策略
- Spring AI 内部执行机制
- SSE 生命周期管理
- LLM 行为约束
- 推理模型防御
- 框架版本兼容性

**核心教训**：
1. **同名工具是万恶之源** — 一个工具只能在一个地方注册
2. **internalToolExecutionEnabled=false 是关键** — 让 Spring AI 不自动执行工具
3. **SSE 生命周期必须正确关闭** — 否则前端永远等待
4. **推理模型不可预测** — 必须有防御性过滤和限制
5. **v2 是完全平行栈** — 不要轻易迁移

**给未来开发者的建议**：
1. 先理解 AG-UI 协议的事件流（RUN_STARTED → TOOL_CALL_* → RUN_FINISHED）
2. 再理解 Spring AI 的工具执行机制（internalToolExecutionEnabled 的作用）
3. 最后理解 CopilotKit 的工具注册机制（v1 vs v2 的差异）
4. 遇到问题时，先看后端日志，再看前端日志，最后用 Playwright E2E 测试

---

> **文档维护者**: Claude Code
> **最后更新**: 2026-06-15
> **状态**: 已验证（全流程 E2E 测试通过）
