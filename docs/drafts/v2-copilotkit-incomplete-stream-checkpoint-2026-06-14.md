# v2 CopilotKit 升级 Checkpoint (2026-06-14)

> 接续指南：本文件记录了"v1.54.0 → v1.60.1 LTS 升级"和"v2 chat + httpRequest 端到端跑通"
> 这两件事的当前状态、已完成项、阻塞项、诊断证据，方便日后直接接着干。

## TL;DR

**已成功**：v2 chat UI 现在能正确接收 LLM 文本流，并进入"inProgress" 状态显示"准备调用 httpRequest..."。
**已根除**：Tomcat 10.1.34 在 SSE + `ResponseEntity<SseEmitter>` 组合下的 `MimeHeaders.setValue NullPointerException`
——这个 bug 让 undici 每次都收到 `bytesRead=0`，触发 CopilotKit v2 chat UI 的
`INCOMPLETE_STREAM` 错误和"卡在 inProgress 不进入 executing"现象。

**已修复 (2026-06-14 晚间)**：
1. **SSE 生命周期 bug**：`SpringAIAgent.executeToolCallsAndReRun()` 在检测到前端工具时
   只发射 `RUN_FINISHED` 但未调用 `subscriber.onRunFinalized()`，导致 SSE 流永远不关闭。
   修复：在前端工具路径和错误处理路径都添加 `subscriber.onRunFinalized()` 调用。
2. **RUN_ERROR 事件缺少 message 字段**：CopilotKit v2 的 Zod 验证要求 `message` 字段，
   但 Java 后端只发送 `error` 字段。修复：在 `RunErrorEvent` 中添加 `getMessage()/setMessage()` 方法。
3. **错误处理未完成 SSE 流**：当 LLM API 返回 429 等错误时，错误处理器未调用
   `subscriber.onRunFinalized()`，导致 BFF 和前端挂起。修复：在所有错误路径都添加
   `onRunFinalized()` 调用。

**仍阻塞**：MiniMax API 429 限流（外部问题），导致 LLM 调用失败。
需要等待限流解除或切换到其他 LLM 提供商。

## 1. 已完成项 (DONE)

### 1.1 CopilotKit 升级 1.54.0 → 1.60.1
- `package.json` 升到 1.60.1 (LTS)
- `frontend/node_modules/@copilotkit/react-core/dist/v2/index.mjs` 的
  `import "./index.css";` 被 patch 成 `/* patched */`（v4 CSS 不兼容 v3）
- v2 入口下 mermaid 缺失 chunks 用 `.mjs` stub 文件补齐
- `useHttpRequestTool.tsx` 从 `useCopilotAction.renderAndWaitForResponse` (v1)
  改写成 `useHumanInTheLoop` (v2)
- `HttpRequestParams` 从 `Parameter[]` 改成 Zod 3.25 schema
  （v2 的 `createToolSchema → schemaToJsonSchema` 读 `schema["~standard"].vendor`，
  不支持 v1 数组）
- `package.json` 加了 `"zod": "^3.25.0"`

### 1.2 Tomcat MimeHeaders NPE 修复 ✅ (2026-06-14)
**根因**：Spring `ResponseEntity<SseEmitter>` 包装返回 + Tomcat 10.1.34 + undici 长 SSE 连接
= `org.apache.tomcat.util.http.MimeHeaders.setValue/getValue/recycle` 在响应回收时
访问 `this.headers[i]` (i 越界/为 null) 抛 NPE，**导致 undici 收到 `bytesRead: 0`**，
浏览器侧 `INCOMPLETE_STREAM` 错误。

**修复位置**：`src/main/java/com/example/demo/controller/AgUiController.java`
**修复方式**：直接返回 `SseEmitter`，用 `HttpServletResponse` 注入 header：
```java
@PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter run(
        @RequestBody AgUiParameters agUiParameters,
        @RequestHeader(value = "Authorization", required = false) String authHeader,
        HttpServletResponse response
) {
    response.setHeader("Cache-Control", "no-cache");
    response.setHeader("X-Accel-Buffering", "no");
    response.setHeader("Connection", "keep-alive");
    // ...
    SseEmitter emitter = agUiService.runAgent(enterpriseAgent, agUiParameters);
    return emitter;  // 不要包 ResponseEntity
}
```

注意：只对 SSE 端点这样改；`/health` 和 `/info` 仍用 `ResponseEntity`（保留 import）。

### 1.3 Backend 验证 ✅
直接 `curl /api/agui` 能收到完整 6 轮 SSE 流：
- `RUN_STARTED` × 6
- `TEXT_MESSAGE_START/CONTENT/END` × 6
- `TOOL_CALL_START/ARGS/END/RESULT` × 6
- `RUN_FINISHED` × 6

### 1.4 浏览器侧 v2 chat 验证 ✅ (部分)
截图 `/tmp/v2-e2e.png` 显示：
- LLM 思考实时流式显示
- "准备调用 httpRequest..." 出现
- 黑色圆点 `●` (inProgress 状态指示)
- 用户消息 "有什么商品可以买？" 已显示

### 1.5 SSE 生命周期修复 ✅ (2026-06-14 晚间)
**根因**：`SpringAIAgent.executeToolCallsAndReRun()` 在检测到前端工具（如 httpRequest）时，
只发射 `RUN_FINISHED` 事件但未调用 `subscriber.onRunFinalized()`。

**影响链**：
1. `subscriber.onRunFinalized()` 未调用
2. → `AgentStreamer` 的 `onRunFinalized` 回调未触发
3. → `eventStream.complete()` 未调用
4. → `emitter::complete` 未调用
5. → SSE 流永远不关闭
6. → BFF 的 `HttpAgent` 永远等待响应
7. → 前端的 `agent.runAgent()` 永远不返回
8. → `processAgentResult` 永远不被调用
9. → `useHumanInTheLoop` 永远卡在 inProgress

**修复位置**：`src/main/java/com/agui/spring/ai/SpringAIAgent.java`

**修复内容**：
1. 在 `executeToolCallsAndReRun()` 的前端工具路径（`!allToolsFound`）添加：
   ```java
   subscriber.onRunFinalized(new AgentSubscriberParams(input.messages(), state, this, input));
   ```

2. 在 `run()` 方法的错误处理器（`err -> { ... }`）添加：
   ```java
   subscriber.onRunFinalized(new AgentSubscriberParams(input.messages(), state, this, input));
   ```

3. 在 `run()` 方法的外层 catch 块添加：
   ```java
   subscriber.onRunFinalized(new AgentSubscriberParams(input.messages(), state, this, input));
   ```

**验证结果**：
- `agent.runAgent()` 现在正常返回（不再挂起）
- `processAgentResult` 被正确调用
- SSE 流在错误情况下也能正确关闭

### 1.6 RUN_ERROR 事件兼容性修复 ✅ (2026-06-14 晚间)
**根因**：CopilotKit v2 的 Zod 验证要求 `RUN_ERROR` 事件包含 `message` 字段，
但 Java 后端的 `RunErrorEvent` 只有 `error` 字段。

**修复位置**：`src/main/java/com/agui/core/event/RunErrorEvent.java`

**修复内容**：
添加 `getMessage()` 和 `setMessage()` 方法，与 `getError()`/`setError()` 共享同一个字段：
```java
public String getMessage() {
    return this.error;
}

public void setMessage(final String message) {
    this.error = message;
}
```

**修复位置**：`src/main/java/com/agui/server/EventFactory.java`

**修复内容**：
确保 `runErrorEvent()` 方法不会传入 null 值：
```java
event.setError(message != null ? message : "Unknown error");
```

**验证结果**：
- RUN_ERROR 事件现在同时包含 `error` 和 `message` 字段
- CopilotKit v2 的 Zod 验证通过
- 前端不再报 "message: Required" 错误

## 2. 阻塞项 (TODO)

### 2.1 ✅ v2 chat 卡 inProgress 不进入 executing (已修复)
**原现象**：
- 浏览器侧 `useHumanInTheLoop` 收到 `TOOL_CALL_START` 后渲染 inProgress 状态
- 但**永远不进入 executing**，所以 `respond` 函数永远不被传入
- 因此 `HttpRequestRender` 里的 `GetRequestProgress` 不会被渲染
- 因此 `executeHttpRequest` + `respond()` 永远不被调用
- 因此 LLM 永远拿不到 tool_result

**根本原因**：SSE 生命周期 bug（见 1.5 节），`agent.runAgent()` 永远不返回。

**修复后状态**：
- `agent.runAgent()` 现在正常返回
- `processAgentResult` 被正确调用
- 但 LLM API 返回 429 限流错误，导致无法完成端到端测试

### 2.2 ❗ MiniMax API 429 限流 (外部问题)
**现象**：
- 后端日志显示 `429 Too Many Requests from POST https://api.minimaxi.com/v1/chat/completions`
- 前端收到 `RUN_ERROR` 事件，错误消息为 "Stream processing failed"
- `processAgentResult` 被调用但 `newMessagesCount: 0`

**影响**：
- 无法完成端到端测试（LLM 调用失败）
- 前端显示错误而非正常回复

**解决方案**：
1. 等待 MiniMax API 限流解除（通常几分钟到几小时）
2. 切换到其他 LLM 提供商（如 DeepSeek、OpenAI）
3. 添加重试逻辑（指数退避）

**当前状态**：等待限流解除

## 3. 接续工作时需要的检查清单

### 3.1 验证 v2 chat 是否收到了 `TOOL_CALL_END`
在 `frontend/hooks/useHttpRequestTool.tsx` 的 `HttpRequestRender` 里加日志：
```ts
function HttpRequestRender(props) {
  console.log('[HttpRequestRender]', { status: props.status, args: props.args });
  // ...
}
```
然后跑 `frontend/probe-v2-e2e.mjs` 看 console，应该输出 3 个阶段：
- `{status: 'inProgress', args: {...}}` （第一次）
- `{status: 'executing', args: {...}, respond: [Function]}` （第二次，应有 respond）
- `{status: 'complete', args: {...}, result: '...', respond: undefined}` （第三次）

如果只看到 inProgress 没后续 = TOOL_CALL_END 没触发状态机。

### 3.2 检查 BFF 路由是否漏发 AG-UI 事件
看 `frontend/app/api/copilotkit/route.ts`：
- 是否所有 SSE 事件都从 Java 后端透传到浏览器？
- `HttpAgent` 在 v1.60.1 下对前端工具调用的转发是否完整？

### 3.3 备选方案：临时用回 v1 路径
如果 (3.1)(3.2) 找不到原因，可以临时在 `frontend/components/CopilotProvider.tsx`
里把 `import { ... } from "@copilotkit/react-core/v2"` 切回 `@copilotkit/react-core`，
v1 的 `useCopilotAction.renderAndWaitForResponse` 路径之前能跑通 (commit c1c4f47)。

## 4. 关键文件 & 启动命令

### 4.1 启动顺序
```bash
# Backend
cd /Users/yangjiefeng/Documents/wubuku/spring-ai-skills-demo
bash -c 'export $(cat .env | grep -v "^#" | xargs) && mvn spring-boot:run -DskipTests > /tmp/backend.log 2>&1' &

# Frontend
cd /Users/yangjiefeng/Documents/wubuku/spring-ai-skills-demo/frontend
npm run dev > /tmp/frontend.log 2>&1 &

# 健康检查
curl http://localhost:8080/api/agui/health   # AG-UI Service is running
curl -I http://localhost:4000                # Next.js 200
```

### 4.2 关键文件
- `src/main/java/com/example/demo/controller/AgUiController.java` — Tomcat NPE 修复
- `frontend/hooks/useHttpRequestTool.tsx` — v2 useHumanInTheLoop 注册
- `frontend/app/api/copilotkit/route.ts` — BFF 路由
- `frontend/components/CopilotProvider.tsx` — Provider 链入口
- `frontend/app/page.tsx` — 主页面 + HttpRequestToolProvider 包装

### 4.3 探针脚本
- `frontend/probe-v2-e2e.mjs` — 主 E2E (输入消息 + 等待响应)
- `frontend/probe-cpk-session.mjs` — 全日志 (console + pageerror + network)
- `frontend/probe-agui-events.sh` — 直接 curl /api/agui
- `frontend/probe-console.mjs` — fetch 拦截 + console 过滤
- `frontend/probe-dom.mjs` — DOM 状态检测
- `frontend/probe-v2-deep.mjs` — 详细 E2E 变体
- `frontend/probe-think-error.mjs` — 错误诊断

### 4.4 输出位置
- `/tmp/v2-e2e.png` — 最新 v2 chat 截图 (显示 inProgress 状态)
- `/tmp/backend.log` — 后端日志
- `/tmp/frontend.log` — 前端日志
- `/tmp/agui-events.txt` — 直接 curl 的 AG-UI 事件流

## 5. 关键 git 状态

```bash
# 主分支
git status
# M frontend/app/page.tsx
# M frontend/components/CopilotProvider.tsx
# M frontend/hooks/useHttpRequestTool.tsx
# M src/main/java/com/agui/spring/ai/SpringAIAgent.java
# M src/main/java/com/example/demo/config/AgUiConfig.java
# M src/main/resources/prompts/enterprise-agent/system-prompt.template
# M src/main/resources/prompts/skills-advisor/mode-rules.template
# ?? compile, docs/drafts/copilotkit-native-httprequest-rescue-plan.md
# ?? docs/drafts/frontend-httprequest-stub-execution-diagnosis.md
# ?? frontend/full-cart-flow.mjs, frontend/probe-*.mjs, frontend/probe-agui-events.sh (10+)
# ?? mvn, touch, v2-initial.png

# 最近提交
df71108 fix: 修复 React 19 Hydration 错误 (添加购物车页面崩溃)
cb2bac6 fix: 按"工具名"去重解决 LLM hanging（同名 httpRequest 冲突）
f889357 add docs
c1c4f47 feat: CopilotKit 原生工具调用重构（useCopilotAction 替代 http-request 代码块解析）
f3dd197 docs: 修正 AG-UI 用户态 Token 透传诊断中的两处细节
```

## 6. 验收标准 (Acceptance)

修复 (2.1) 后，应能在 v2 chat UI 看到：
1. 输入"有什么商品" → LLM 流式显示思考
2. 状态变 inProgress："准备调用 httpRequest..."
3. **状态变 executing**：GET 请求时显示"正在执行 GET /api/products ..."
4. 实际发出 HTTP 请求到 `http://localhost:8080/api/products` (可见 backend 日志)
5. 状态变 complete："✓ httpRequest 完成 (200)"
6. LLM 收到 tool_result 后**用自然语言组织商品列表**作为最终回答
7. 全程 console 无 PAGEERROR

如果 (3) (4) (5) 中任一缺失 → 接续 (2.1) 的调试。

## 7. 已知已修复但需 commit 的变更

- `src/main/java/com/example/demo/controller/AgUiController.java` (Tomcat NPE)
- 临时在 `frontend/hooks/useHttpRequestTool.tsx` 加的 console.log (待移除)
- 需要更新 `transform-v2-css.mjs` postinstall 脚本
- 需要更新 `CLAUDE.md` 记录 v2 升级结果
