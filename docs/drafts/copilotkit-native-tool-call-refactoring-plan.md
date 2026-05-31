# CopilotKit 前端工具调用重构规划

> **版本**: 1.0
> **创建时间**: 2026-05-31
> **状态**: 待批准
> **目标**: 将"http-request 代码块解析"重构为 CopilotKit 原生工具调用机制

---

## 0. 实施环境与上下文

> **关键**：此节确保文档"自包含"——即使中断后重新开始实施，也无需回忆环境细节。

### 0.1 运行环境

| 组件 | 版本/说明 |
|------|----------|
| Java | JDK 21+ |
| Maven | 3.9+ |
| Node.js | 20+ |
| npm | 10+ |
| PostgreSQL | 16+（需安装 pgvector 扩展） |
| 数据库名 | `spring-ai-skills-demo` |
| pgvector | `CREATE EXTENSION vector;` |

### 0.2 启动服务

**启动后端**（Java Spring Boot，端口 8080）：

```bash
# 1. 确保 PostgreSQL 已启动
# 2. 杀死可能残留的进程
lsof -ti:8080 -sTCP:LISTEN | xargs -r kill -9 2>/dev/null; echo "Killed server on port 8080"

# 3. 加载环境变量并启动（必须用 bash -c 包裹，避免 zsh 解析错误）
cd /Users/yangjiefeng/Documents/wubuku/spring-ai-skills-demo
bash -c 'export $(cat .env | grep -v "^#" | grep -v "^$" | xargs) && mvn spring-boot:run -DskipTests'
```

**启动前端**（Next.js，端口 4000）：

```bash
cd /Users/yangjiefeng/Documents/wubuku/spring-ai-skills-demo/frontend
npm run dev
# 访问 http://localhost:4000
```

### 0.3 前端环境变量（可选）

前端项目根目录 `frontend/.env.local`（如不存在则创建）：

```bash
# 可选：覆盖 Java 后端地址（默认 http://localhost:8080）
NEXT_PUBLIC_JAVA_BACKEND_URL=http://localhost:8080
```

### 0.4 关键依赖版本

**前端**（`frontend/package.json`）：

| 包 | 版本 |
|----|------|
| `@copilotkit/react-core` | ^1.54.0 |
| `@copilotkit/react-ui` | ^1.54.0 |
| `@copilotkit/runtime` | ^1.54.0 |
| `@ag-ui/client` | ^0.0.47 |
| `next` | 15.1.6 |
| `react` | ^19.0.0 |
| `typescript` | ^5 |

**后端关键依赖**：

| 组件 | 说明 |
|------|------|
| `ag-ui-4j` 子模块 | AG-UI 协议 Java 实现（`src/main/java/com/agui/`） |
| `SpringAIAgent` | `com.agui.spring.ai.SpringAIAgent` — 核心桥梁 |
| `ToolMapper` | 将前端 AG-UI 工具转换为 Spring AI ToolCallback |

### 0.5 涉及文件速查表

| 文件 | 角色 | 重构操作 |
|------|------|----------|
| `frontend/app/page.tsx` | 主页面，含 `CustomAssistantMessage` + `extractHttpRequestMeta` | 修改：移除旧逻辑，集成 `useHttpRequestTool` |
| `frontend/components/CopilotAssistantMessage.tsx` | 第二份 `extractHttpRequestMeta`（当前未被引用） | 简化或删除 |
| `frontend/components/ConfirmDialogContainer.tsx` | 确认对话框 + `executeHttpRequest` | Phase 3 删除 |
| `frontend/components/CopilotProvider.tsx` | CopilotKit Runtime 包装（auth headers） | 不修改 |
| `frontend/app/layout.tsx` | 根布局（包含 `<CopilotProvider>`） | 不修改 |
| `frontend/app/api/copilotkit/route.ts` | BFF 代理（透传 auth） | 不修改 |
| `src/main/java/.../SkillTools.java` | 后端工具定义 | Phase 3 可选移除 |
| `src/main/java/.../SkillsAdvisor.java` | 系统提示注入 | 不修改（Phase 1-2） |
| `src/main/java/.../AgUiConfig.java` | SpringAIAgent 配置 | 不修改 |
| `src/main/resources/prompts/.../mode-rules.template` | LLM 工具使用规则 | 修改：引导使用 `httpRequest` |
| `src/main/resources/prompts/.../system-prompt.template` | 系统提示词（含 `{{HTTP_TOOL_NAME}}`） | 不修改（Phase 1-2），Phase 3 可更新 |
| `src/main/java/com/agui/spring/ai/SpringAIAgent.java` | 前端工具注入入口（line 256-270） | 不修改 |

### 0.6 测试脚本

| 脚本 | 路径 | 用途 |
|------|------|------|
| `test-streaming.sh` | 项目根目录 | 流式多模态聊天测试 |
| `test-agui-jwt-full.sh` | 项目根目录 | AG-UI JWT 认证流程测试 |
| `test.sh` | 项目根目录 | 通用 E2E 测试 |

### 0.7 认证 Token 格式

用户登录后 `auth_token` 存储在 `localStorage`：
- 格式：`base64(username:password)`
- 示例用户：`user1 / password1` → token: `dXNlcjE6cGFzc3dvcmQx`

---

## 1. 现状分析

### 1.1 当前实现的"重新发明轮子"问题

#### 当前数据流（绕过 CopilotKit 原生机制）

```
用户消息 → CopilotKit Runtime → Java 后端 Agent
                                      ↓
                              LLM 调用 buildHttpRequest 工具
                                      ↓
                              返回 JSON 元数据（不执行请求）
                                      ↓
LLM 在回复中输出 http-request 代码块
                                      ↓
前端 AssistantMessage 组件解析消息内容，提取代码块（page.tsx 中的 CustomAssistantMessage）
                                      ↓
前端解析 JSON，渲染 ConfirmDialogContainer
                                      ↓
用户确认 → 前端手动执行 fetch 请求
```

**问题**：
1. **绕过了 CopilotKit 的工具调用机制** — 应该使用 `TOOL_CALL_*` 事件
2. **绕过了 CopilotKit 的 Human-in-the-loop** — 应该使用 `useInterrupt`
3. **代码重复** — `extractHttpRequestMeta` 在 `page.tsx` 和 `CopilotAssistantMessage.tsx` 两处分别实现（注意：`page.tsx` 使用 `CustomAssistantMessage` 内联组件拦截消息内容，`CopilotAssistantMessage.tsx` 为独立导出但当前未被 `page.tsx` 引用，可能是遗留文件）
4. **状态管理 workaround** — 使用 module-level `confirmStateCache` 解决 CopilotKit 组件重挂载问题
5. **多处 auth 处理** — `CopilotProvider.tsx`（Runtime 请求认证头）、`route.ts`（BFF 透传）、`ConfirmDialogContainer.tsx`（前端直接执行 HTTP 请求）形成认证透传链路，角色不同但缺乏统一抽象

#### 涉及的文件

| 文件 | 问题 |
|------|------|
| `frontend/app/page.tsx:23-78` | `extractHttpRequestMeta` 重复实现 #1 |
| `frontend/components/CopilotAssistantMessage.tsx:15-62` | `extractHttpRequestMeta` 重复实现 #2 |
| `frontend/components/ConfirmDialogContainer.tsx:30-91` | `executeHttpRequest` 手动 HTTP 执行 |
| `frontend/components/ConfirmDialogContainer.tsx:107` | `confirmStateCache` module-level 缓存 |
| `src/main/resources/prompts/skills-advisor/mode-rules.template:7-43` | 指导 LLM 输出 http-request 代码块 |
| `src/main/java/com/example/demo/agent/SkillTools.java:85-122` | `buildHttpRequest` 工具返回 JSON 元数据 |

### 1.2 CopilotKit 原生机制回顾

根据 `docs/drafts/copilotkit-exploration.md` 和官方文档：

#### 原生工具调用流程（推荐方案 A）

```
用户消息 → CopilotKit Runtime → Java 后端 Agent
                                      ↓
                              LLM 调用 httpRequest 工具
                                      ↓
                    SpringAIAgent 发射 TOOL_CALL_START/ARGS/END 事件
                                      ↓
前端 CopilotKit 接收 TOOL_CALL_* 事件
                                      ↓
useCopilotAction 渲染确认 UI（renderAndWaitForResponse）
                                      ↓
用户确认 → executeHttpRequest 执行 → 结果返回 Agent
                                      ↓
Agent 继续推理 → 最终回复
```

#### 原生 Interrupt 流程（推荐方案 B）

```
用户消息 → Agent 执行 → 调用 interrupt({ slots: [...] })
                                      ↓
                    发射 on_interrupt 自定义事件 + RUN_FINISHED
                                      ↓
前端 useInterrupt 渲染交互 UI
                                      ↓
用户选择 → resolve() → forwardedProps.command.resume
                                      ↓
Agent 恢复执行
```

### 1.3 技术选型决策

**推荐方案：使用 CopilotKit 原生 `useCopilotAction`（方案 A）**

原因：
1. 与现有 `buildHttpRequest` 语义最接近（构建请求 + 用户确认 + 执行）
2. 直接支持 `renderAndWaitForResponse`，适合用户确认场景
3. CopilotKit 自动处理 `TOOL_CALL_*` 事件流
4. `useInterrupt`（方案 B）更适合 Agent 主动暂停等待复杂输入的场景

---

## 2. 重构目标

### 2.1 功能目标

| 目标 | 当前实现 | 重构后 |
|------|----------|--------|
| HTTP 请求确认 | LLM 输出 http-request 代码块，前端解析渲染确认对话框 | 使用 `useCopilotAction` 注册 `httpRequest` 工具，`renderAndWaitForResponse` 原生渲染 |
| 工具执行 | 前端手动 fetch 执行 HTTP 请求 | 用户确认后通过 `respond` 回调执行，结果透传给 Agent |
| 状态同步 | module-level `confirmStateCache` workaround | CopilotKit 原生状态管理 |
| Auth 传递 | 多处 auth 处理（CopilotProvider → route.ts → ConfirmDialogContainer），形成链路但缺乏统一抽象 | 从 `localStorage` 获取 token（保持现有方式），由 `useCopilotAction` 的 `executeHttpRequest` 统一处理 |

### 2.2 非功能目标

| 目标 | 当前 | 重构后 |
|------|------|--------|
| 代码行数（前端） | ~400 行（含重复逻辑） | ~200 行 |
| 代码重复 | `extractHttpRequestMeta` 两处实现 | 单一工具定义 |
| 可维护性 | 低（特殊代码块格式，无类型提示） | 高（原生 TypeScript 类型） |
| CopilotKit 版本兼容性 | 使用 v1 兼容 API | 使用 v1 兼容 API（`useCopilotAction`） |

### 2.3 限制条件

1. **渐进式重构** — 新旧实现可以共存，逐步切换。在 Phase 3 之前不修改 SkillTools.java
2. **向后兼容** — 保留现有功能，逐步废弃 http-request 模式
3. **前端优先** — 优先通过前端 `useCopilotAction` 注册工具，后端工具仅需根据 Phase 3 决定是否移除

---

## 3. 技术方案

### 3.1 整体架构

#### 关键机制：前工具栏如何传递给 LLM

本方案的核心依赖：**CopilotKit 自动将前端注册的 `useCopilotAction` 工具包含在 `RunAgentInput.tools` 中**，`SpringAIAgent` 接收后通过 `ToolMapper` 转换为 Spring AI 的 `ToolCallback`，与 Java `@Tool` 注解的工具一起提供给 LLM。

```
┌─ 前端 ───────────────────────────────────────────┐
│ useCopilotAction({ name: "httpRequest", ... })     │
│   → CopilotKit 自动收集为工具定义                   │
│   → 发送到 Runtime 时包含在 RunAgentInput.tools[]   │
└────────────────────┬──────────────────────────────┘
                     ↓
┌─ BFF (route.ts) ──────────────────────────────────┐
│ → HttpAgent 转发 RunAgentInput 到 Java 后端        │
└────────────────────┬──────────────────────────────┘
                     ↓
┌─ Java 后端 ───────────────────────────────────────┐
│ SpringAIAgent.getChatRequest()                     │
│   ① input.tools() 遍历前端工具                     │
│   ② ToolMapper.toSpringTool() 转换为 ToolCallback  │
│   ③ chatRequest.toolCallbacks() 注册到 Spring AI   │
│                                                    │
│   LLM 看到的工具列表 = 前端工具 + 后端 @Tool 工具    │
│                                                    │
│   当 LLM 调用 httpRequest：                         │
│   → 发出 TOOL_CALL_START/ARGS/END 事件             │
│   → CopilotKit 前端拦截，渲染 useCopilotAction UI   │
│   → 用户确认后 respond() 返回结果                   │
└────────────────────────────────────────────────────┘
```

**验证来源**：`SpringAIAgent.java:256-270` 已有 `input.tools()` 处理逻辑，无需修改后端代码即可支持前端工具注入。

```
┌─────────────────────────────────────────────────────────────────┐
│                        前端（重构后）                              │
│                                                                 │
│  CopilotKit Runtime (Browser)                                    │
│       ↓                                                         │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ useCopilotAction({                                        │   │
│  │   name: "httpRequest",                                   │   │
│  │   parameters: [...],                                     │   │
│  │   renderAndWaitForResponse: ({ args, respond }) => {     │   │
│  │     return <HttpConfirmationDialog onConfirm={...} />     │   │
│  │   }                                                      │   │
│  │ })                                                       │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│  CopilotPopup                                                   │
│       ↓                                                         │
│  /api/copilotkit (BFF)                                         │
└─────────────────────────────────────────────────────────────────┘
                           ↓ HTTP POST + SSE
┌─────────────────────────────────────────────────────────────────┐
│                        Java 后端（简化）                          │
│                                                                 │
│  SpringAIAgent                                                 │
│       ↓                                                         │
│  SkillTools (后端工具: loadSkill, readSkillReference 等)         │
│       ↓                                                         │
│  TOOL_CALL_RESULT 事件                                          │
│                                                                 │
│  注意：httpRequest 工具移到前端，Java 后端不再需要它              │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 前端修改

#### 3.2.1 新增 `frontend/hooks/useHttpRequestTool.ts`

> **API 选择说明**：`useCopilotAction`（v1 兼容 API）支持两种渲染模式：
>
> - `render` + `handler`：用于自动执行的工具，UI 渲染后自动调用 `handler` 函数
> - `renderAndWaitForResponse`：用于需要用户交互确认的工具，通过 `respond` 回调返回用户决定
>
> 对于 HTTP 请求确认场景，需要使用 `renderAndWaitForResponse`，因为需要用户点击"确认"或"取消"。

```typescript
"use client";

import { useCopilotAction } from "@copilotkit/react-core";

/**
 * HTTP 请求参数 Schema
 * 注意：使用 v1 格式的 Parameter[]，与 useCopilotAction 兼容
 */
const HttpRequestParams = [
  {
    name: "method",
    type: "string",
    enum: ["GET", "POST", "PUT", "DELETE"],
    description: "HTTP 方法",
    required: true,
  },
  {
    name: "url",
    type: "string",
    description: "API 路径（相对路径或完整 URL）",
    required: true,
  },
  {
    name: "params",
    type: "string",  // 注意：使用 "string" 而非 "object"，LLM 传入 JSON 字符串
    description: "路径/查询参数，JSON 格式的键值对字符串，如 '{\"productId\":\"1\"}'",
    required: false,
  },
  {
    name: "body",
    type: "string",
    description: "请求体（仅 POST/PUT，JSON 字符串）",
    required: false,
  },
] as const;

/**
 * 执行 HTTP 请求
 *
 * 注意：使用固定的 JAVA_BACKEND_BASE 而非 window.location.origin，
 * 以确保 LLM 传入的相对路径（如 /api/products）转发到 Java 后端（8080），
 * 而不是 Next.js 前端（4000）。与当前 ConfirmDialogContainer.tsx 保持一致。
 */
const JAVA_BACKEND_BASE = typeof window !== 'undefined'
  ? (process.env.NEXT_PUBLIC_JAVA_BACKEND_URL || 'http://localhost:8080')
  : 'http://localhost:8080';

async function executeHttpRequest(params: {
  method: string;
  url: string;
  params?: string;  // LLM 传入 JSON 字符串，需解析
  body?: string;
}): Promise<{ success: boolean; status: number; body: string; error?: string }> {
  const { method, url, params: paramsJson, body } = params;

  // 解析 params JSON 字符串
  let pathParams: Record<string, string> = {};
  if (paramsJson) {
    try {
      pathParams = JSON.parse(paramsJson);
    } catch {
      console.warn('[executeHttpRequest] Failed to parse params JSON:', paramsJson);
    }
  }

  // 解析 URL：绝对 URL 直接使用，相对路径拼接 Java 后端地址
  let fullUrl = url.startsWith('http')
    ? url
    : `${JAVA_BACKEND_BASE}${url.startsWith('/') ? '' : '/'}${url}`;

  // 拼接查询参数
  if (Object.keys(pathParams).length > 0) {
    fullUrl += '?' + new URLSearchParams(pathParams).toString();
  }

  // 获取 auth token
  const token = typeof window !== 'undefined'
    ? localStorage.getItem('auth_token')
    : null;

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  try {
    const resp = await fetch(fullUrl, {
      method,
      headers,
      body: body ? JSON.stringify(JSON.parse(body)) : undefined, // body 需要是 JSON 字符串
    });

    const responseBody = await resp.text();

    return {
      success: resp.ok,
      status: resp.status,
      body: responseBody,
    };
  } catch (e: any) {
    return {
      success: false,
      status: 0,
      body: '',
      error: e.message,
    };
  }
}

/**
 * 注册 HTTP 请求工具的 Hook
 * 使用 renderAndWaitForResponse 实现用户确认模式
 */
export function useHttpRequestTool() {
  useCopilotAction({
    name: "httpRequest",
    description: "发送 HTTP 请求调用 REST API，支持 GET/POST/PUT/DELETE。需要认证的操作会自动使用用户的 access token。",
    parameters: HttpRequestParams,
    renderAndWaitForResponse: ({ args, status, respond }) => {
      // GET 请求直接执行（无需确认），其他方法显示确认对话框
      const isReadOnly = args.method === 'GET';
      if (isReadOnly) {
        executeHttpRequest({
          method: args.method,
          url: args.url,
          params: args.params,
          body: args.body,
        }).then((result) => {
          respond?.({
            success: result.success,
            status: result.status,
            body: result.body,
            error: result.error,
          });
        });
        return null;  // 不渲染任何 UI
      }

      // 渲染确认对话框（POST/PUT/DELETE）
      return (
        <HttpConfirmationDialog
          method={args.method}
          url={args.url}
          params={args.params}
          body={args.body}
          isExecuting={status === "executing"}
          onConfirm={async () => {
            const result = await executeHttpRequest({
              method: args.method,
              url: args.url,
              params: args.params,
              body: args.body,
            });
            respond?.({
              success: result.success,
              status: result.status,
              body: result.body,
              error: result.error,
            });
          }}
          onCancel={() => respond?.({ cancelled: true, error: "用户取消" })}
        />
      );
    },
  });
}

/**
 * 确认对话框组件
 */
function HttpConfirmationDialog({
  method,
  url,
  params: paramsJson,
  body,
  isExecuting,
  onConfirm,
  onCancel,
}: {
  method: string;
  url: string;
  params?: string;  // LLM 传入的 JSON 字符串
  body?: string;
  isExecuting: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  // 解析 params JSON 字符串用于显示
  let params: Record<string, string> | undefined;
  if (paramsJson) {
    try {
      params = JSON.parse(paramsJson);
    } catch {
      // 解析失败，直接显示原始字符串
    }
  }
  return (
    <div className="bg-white dark:bg-gray-800 rounded-lg shadow-xl border border-gray-200 dark:border-gray-700 p-4 max-w-md my-2">
      <div className="flex items-center gap-2 mb-3">
        <span className="text-2xl">⚠️</span>
        <h3 className="text-lg font-semibold text-gray-900 dark:text-white">操作确认</h3>
      </div>
      <div className="bg-gray-50 dark:bg-gray-900 rounded-lg p-3 mb-4 text-sm">
        <div className="flex items-center gap-2 mb-2">
          <span className={[
            'px-2 py-0.5 rounded text-xs font-medium uppercase',
            method === 'POST' ? 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200' : '',
            method === 'PUT' ? 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200' : '',
            method === 'DELETE' ? 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200' : '',
            !['POST', 'PUT', 'DELETE'].includes(method) ? 'bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200' : '',
          ].filter(Boolean).join(' ')}>
            {method}
          </span>
          <code className="text-gray-800 dark:text-gray-200 font-mono text-xs break-all">{url}</code>
        </div>
        {params && Object.keys(params).length > 0 ? (
          <div className="text-xs text-gray-500 dark:text-gray-400 mt-1">
            参数：{new URLSearchParams(params).toString()}
          </div>
        ) : paramsJson && (
          <div className="text-xs text-gray-500 dark:text-gray-400 mt-1">
            参数：{paramsJson}
          </div>
        )}
        {body && (
          <details className="mt-2">
            <summary className="cursor-pointer text-gray-500 dark:text-gray-400 text-xs hover:text-gray-700 dark:hover:text-gray-200">
              请求体
            </summary>
            <pre className="mt-1 text-xs bg-gray-100 dark:bg-gray-800 p-2 rounded overflow-x-auto max-h-32">
              {body}
            </pre>
          </details>
        )}
      </div>
      <div className="flex justify-end gap-2">
        <button
          onClick={onCancel}
          disabled={isExecuting}
          className="px-4 py-2 rounded-lg text-sm font-medium bg-gray-100 dark:bg-gray-700 text-gray-700 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-gray-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          取消
        </button>
        <button
          onClick={onConfirm}
          disabled={isExecuting}
          className="px-4 py-2 rounded-lg text-sm font-medium bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center gap-2"
        >
          {isExecuting && (
            <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24" fill="none">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
            </svg>
          )}
          {isExecuting ? '执行中...' : '确认执行'}
        </button>
      </div>
    </div>
  );
}
```

#### 3.2.2 修改 `frontend/app/page.tsx`

**变更点**：

1. 移除 `extractHttpRequestMeta` 函数
2. 移除 `CustomAssistantMessage` 组件（不再需要解析 http-request 代码块）
3. 移除 `AssistantMessage` prop 注入（改用默认 AssistantMessage）
4. 保留 `markdownTagRenderers`（表格渲染等无关联覆盖）
5. 添加 `useHttpRequestTool` 调用
6. 移除 `confirmedRequests` Set 和 `getRequestKey` 函数

```typescript
"use client";

import React from "react";
import { CopilotPopup } from "@copilotkit/react-ui";
import { useHttpRequestTool } from "@/hooks/useHttpRequestTool";
import { AuthProvider, useAuth } from "@/components/AuthProvider";

function HttpRequestToolProvider({ children }: { children: React.ReactNode }) {
  useHttpRequestTool();
  return children;
}

function HomeContent() {
  const { token, username } = useAuth();
  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-100 dark:from-gray-900 dark:to-gray-800">
      <AuthBar />
      <main className="container mx-auto px-4 py-8">
        {/* Header + Feature Cards + Usage Instructions 保持不变 */}

        <HttpRequestToolProvider>
          <CopilotPopup
            instructions="你是企业智能助手，帮助员工解答业务问题、查询数据、执行操作。"
            labels={{
              title: "企业智能助手",
              initial: "你好！我是企业智能助手，有什么可以帮助你的吗？",
              placeholder: "输入你的问题...",
            }}
            markdownTagRenderers={{
              // 保留表格渲染（与重构无关，维持现有功能）
              table: ({ children, ...props }: any) => (
                <div className="overflow-x-auto my-4">
                  <table className="min-w-full border-collapse border border-gray-300 dark:border-gray-600" {...props}>
                    {children}
                  </table>
                </div>
              ),
              thead: ({ children, ...props }: any) => (
                <thead className="bg-gray-100 dark:bg-gray-700" {...props}>{children}</thead>
              ),
              tbody: ({ children, ...props }: any) => (
                <tbody className="divide-y divide-gray-200 dark:divide-gray-700" {...props}>{children}</tbody>
              ),
              tr: ({ children, ...props }: any) => (
                <tr className="hover:bg-gray-50 dark:hover:bg-gray-800" {...props}>{children}</tr>
              ),
              th: ({ children, ...props }: any) => (
                <th className="border border-gray-300 dark:border-gray-600 px-4 py-2 text-left font-semibold text-gray-900 dark:text-gray-100" {...props}>{children}</th>
              ),
              td: ({ children, ...props }: any) => (
                <td className="border border-gray-300 dark:border-gray-600 px-4 py-2 text-gray-700 dark:text-gray-300" {...props}>{children}</td>
              ),
            }}
          />
        </HttpRequestToolProvider>
      </main>
    </div>
  );
}

export default function Home() {
  return (
    <AuthProvider>
      <HomeContent />
    </AuthProvider>
  );
}
```

> **注意**：`AuthBar`、`LoginModalWrapper`、`FeatureCard` 等辅助组件保持不变，此处省略以聚焦变更点。

#### 3.2.3 简化 `frontend/components/CopilotAssistantMessage.tsx`

> **注意**：此组件当前未被 `page.tsx` 引用（`page.tsx` 使用内联的 `CustomAssistantMessage`）。该文件可能是遗留代码，重构后可直接删除或简化为纯透传。它包含第二份 `extractHttpRequestMeta` 实现和 `[CONFIRM_REQUIRED]` 前缀清理逻辑，这些在迁移到 CopilotKit 原生工具调用后全部不再需要。

```typescript
"use client";

import React from "react";
import {
  AssistantMessage as DefaultAssistantMessage,
  type AssistantMessageProps,
} from "@copilotkit/react-ui";

// 重构后，CopilotAssistantMessage 可以大幅简化或移除
// 因为工具调用由 CopilotKit 原生处理，不再需要解析 http-request 代码块
// 原有的 [CONFIRM_REQUIRED] 前缀清理也一并移除

export function CopilotAssistantMessage(props: AssistantMessageProps) {
  // 直接使用默认渲染，不再需要提取 http-request 代码块
  return <DefaultAssistantMessage {...props} />;
}
```

#### 3.2.4 新增 `frontend/hooks/index.ts`（可选）

导出所有工具 hooks，保持一致性和可发现性。

### 3.3 后端修改（最小化）

#### 3.3.1 后端工具调整（Phase 3 可选）

**当前状态**：
- `SkillTools.httpRequest()` — 后端直接执行 HTTP 请求
- `SkillTools.buildHttpRequest()` — 返回 JSON 元数据，供 LLM 输出 http-request 代码块

**问题**：
- 这两个工具都与前端确认模式冲突
- `httpRequest` 在后端执行，无法获取用户 browser 的 auth token
- `buildHttpRequest` 导致 LLM 输出特殊格式的文本，而非原生工具调用

**重构后的解决方案（注意：此步骤属于 Phase 3，渐进式废弃）**：

1. **`httpRequest` 工具**：当 LLM 直接调用前端的 `httpRequest` 工具后，后端这个工具就不再被调用。可保留作为开源 API 直接调用的降级方案
2. **`buildHttpRequest` 工具**：当 LLM 通过前端的 `httpRequest` 工具处理确认流程后，此工具不再被调用。Phase 3 可安全移除

**Phase 1-2 期间 Java 后端继续保留**：
- `loadSkill` — 加载技能
- `readSkillReference` — 读取技能参考
- `httpRequest` — （保留，作为降级方案）
- `buildHttpRequest` — （保留，作为降级方案）
- `executeHttpRequest` 私有方法 — （保留，依赖它的其他逻辑）

> **⚠️ 工具名冲突说明**：前端 `useCopilotAction({ name: "httpRequest" })` 与后端 `@Tool` 的 `httpRequest` 同名。Spring AI 会同时注册两个同名工具，LLM 可能在两者之间随机选择。Phase 1-2 期间需要通过**前端工具的描述更具吸引力**（如明确提及"自动使用用户 access token"和"暂停确认"等前端特有功能）来引导 LLM 优先选择前端版本。Phase 3 移除后端 `httpRequest` 后可彻底消除此冲突。

**Phase 3 可选择性移除**：
- `httpRequest` 和 `buildHttpRequest` 两个 `@Tool` 方法
- 对应的私有 `executeHttpRequest` 方法及 JWT 提取逻辑（如无其他调用者）

#### 3.3.2 修改 `mode-rules.template`

**变更前**（指导 LLM 输出 http-request 代码块）：
```markdown
7. 【用户确认模式 - 核心流程】
   步骤1：调用 buildHttpRequest 工具...
   步骤2：工具会返回 JSON 格式的请求元数据
   步骤3：在你的回复中先用自然语言...然后**必须**输出 http-request 代码块
```

**变更后**（指导 LLM 直接调用 httpRequest 工具）：
```markdown
6. 【HTTP 请求工具使用规则】
   - **httpRequest 工具**：用于所有 HTTP API 调用
   - 调用时传入 method、url、params、body 参数
   - 工具会自动处理认证（使用用户的 access token）
   - 如果操作需要用户确认，工具会暂停并显示确认对话框
```

### 3.4 认证传递机制

#### 当前问题

| 位置 | Auth 处理 |
|------|-----------|
| `CopilotProvider.tsx` | 设置 `headers` prop |
| `route.ts` | 透传 auth header 到 Java 后端 |
| `ConfirmDialogContainer.tsx` | 从 `localStorage` 获取 token |

#### 重构后

直接使用 `localStorage` 获取 token（保持现有方式）：

```typescript
// 在 useHttpRequestTool 中
const token = localStorage.getItem('auth_token');

const headers: Record<string, string> = {
  'Content-Type': 'application/json',
};
if (token) {
  headers['Authorization'] = `Bearer ${token}`;
}
```

**决策**：
1. 保持从 `localStorage` 获取 token，与当前 `ConfirmDialogContainer` 一致
2. 避免引入新的复杂性（如 `useAgentContext`），因为当前方式已经工作正常
3. `useCopilotContext` 可用于传递其他上下文信息，但 auth token 仍使用 localStorage

---

## 4. 实施步骤

> **每个步骤包含"操作"和"验证"两部分，确保每步都可独立验证。**

### Phase 1：基础设施（准备阶段）

#### Step 1.1：创建 `useHttpRequestTool` Hook

**文件**：`frontend/hooks/useHttpRequestTool.ts`（新建）

**操作**：
1. 创建 `frontend/hooks/` 目录
2. 创建 `useHttpRequestTool.ts`，写入 Section 3.2.1 的完整代码
3. 创建 `frontend/.env.local`，添加 `NEXT_PUBLIC_JAVA_BACKEND_URL=http://localhost:8080`（如尚未创建）

**验证**：
```bash
# 确保前端能编译通过（尚未集成到 page.tsx，只需确认无语法错误）
cd frontend && npx tsc --noEmit 2>&1 | grep -i "useHttpRequestTool" || echo "No errors in useHttpRequestTool"
```

**验收标准**：
- [ ] Hook 可以注册到 CopilotKit
- [ ] `executeHttpRequest` 可以正确执行 HTTP 请求
- [ ] `HttpConfirmationDialog` 可以正确渲染确认对话框

#### Step 1.2：简化 `CopilotAssistantMessage`

**文件**：`frontend/components/CopilotAssistantMessage.tsx`

**操作**：替换为 Section 3.2.3 的简化版本（纯透传 `DefaultAssistantMessage`）

**验证**：
```bash
cd frontend && npx tsc --noEmit 2>&1 | grep -i "CopilotAssistantMessage" || echo "No errors"
```

**验收标准**：
- [ ] 组件仍然正常工作
- [ ] 不再解析 http-request 代码块

### Phase 2：核心重构

#### Step 2.1：集成 `useHttpRequestTool` 到 `page.tsx`

**文件**：`frontend/app/page.tsx`

**操作**：
1. 按 Section 3.2.2 修改 `page.tsx`：移除 `extractHttpRequestMeta`、`CustomAssistantMessage`、`confirmedRequests`、`getRequestKey`
2. 添加 `import { useHttpRequestTool }` 和 `HttpRequestToolProvider` 包装 `CopilotPopup`
3. 保留 `markdownTagRenderers`（表格渲染）
4. 保留 `AuthBar`、`LoginModalWrapper`、`FeatureCard` 等组件

**验证**：
```bash
# 1. 前端编译检查
cd frontend && npx tsc --noEmit

# 2. 启动前端，访问 http://localhost:4000，确认页面正常渲染
cd frontend && npm run dev

# 3. 打开浏览器 Console，确认无报错，httpRequest 工具已注册
#    在 Console 中应能看到正常的日志输出
```

**验收标准**：
- [ ] CopilotPopup 正确渲染
- [ ] httpRequest 工具可以正常调用
- [ ] 确认对话框正确显示
- [ ] 表格渲染正常（无回归）

#### Step 2.2：修改后端提示词

**文件**：`src/main/resources/prompts/skills-advisor/mode-rules.template`

**操作**：
1. 备份原文件：`cp mode-rules.template mode-rules.template.backup`
2. 替换规则 7-9 的内容为 Section 3.3.2 的新版本

**验证**：
```bash
# 重启后端使提示词生效
lsof -ti:8080 -sTCP:LISTEN | xargs -r kill -9 2>/dev/null
bash -c 'export $(cat .env | grep -v "^#" | grep -v "^$" | xargs) && mvn spring-boot:run -DskipTests'

# 运行集成测试验证 LLM 行为
bash test-agui-jwt-full.sh
```

**验收标准**：
- [ ] LLM 不再输出 http-request 代码块
- [ ] LLM 直接调用 httpRequest 工具

### Phase 3：清理

> **Phase 3 前置条件**：Phase 2 已稳定运行，确认新方案无回归。

#### Step 3.1：移除 `page.tsx` 中的重复代码

**操作**：从 `page.tsx` 中删除已不再引用的代码

**移除**：
- `extractHttpRequestMeta` 函数（约 60 行）
- `getRequestKey` 函数（约 5 行）
- `confirmedRequests` Set（约 2 行）
- `CustomAssistantMessage` 相关逻辑
- 未使用的 import（`ConfirmDialogContainer`、`HttpRequestMeta` 等）

**验证**：
```bash
cd frontend && npx tsc --noEmit
# 确认无编译错误，无未使用变量的警告
```

#### Step 3.2：删除 `ConfirmDialogContainer.tsx`

**原因**：
- 新方案中，`HttpConfirmationDialog` 组件已内联到 `useHttpRequestTool.ts`
- `executeHttpRequest` 逻辑也已内联到组件的 `onConfirm` 回调中
- 不再需要独立的 `ConfirmDialogContainer.tsx`

**操作**：
1. 检查是否有其他文件 `import` 了 `ConfirmDialogContainer`（`grep -r "ConfirmDialogContainer" frontend/`）
2. 如无引用，删除文件

**验证**：
```bash
cd frontend && npx tsc --noEmit
# 启动前端，确认功能正常
npm run dev
```

**删除文件**：`frontend/components/ConfirmDialogContainer.tsx`

#### Step 3.3：可选移除后端 HTTP 工具

**文件**：`src/main/java/com/example/demo/agent/SkillTools.java`

**移除**：
- `httpRequest()` 方法 — 前端 `useCopilotAction` 已接管
- `buildHttpRequest()` 方法 — LLM 不再需要此工具
- `executeHttpRequest()` 私有方法及 `extractJwt()` 辅助方法（如无其他调用者）

**操作**：
1. 删除上述方法
2. 删除不再需要的 import（`HttpEntity`、`HttpMethod`、`BearerAuth` 等）
3. 同步更新 `SkillsAdvisor.getHttpToolName()` 返回值从 `"buildHttpRequest"` 改为 `"httpRequest"`

**验证**：
```bash
# 编译后端
mvn compile -DskipTests

# 重启并测试
lsof -ti:8080 -sTCP:LISTEN | xargs -r kill -9 2>/dev/null
bash -c 'export $(cat .env | grep -v "^#" | grep -v "^$" | xargs) && mvn spring-boot:run -DskipTests'
bash test-agui-jwt-full.sh
```

**注意**：此步骤在 Phase 3 执行，必须在前端工具已验证稳定后。

#### Step 3.4：更新文档

**操作**：
- `docs/CLAUDE.md` — 更新前端实现说明，将工具调用方式从"http-request 代码块解析"更新为"CopilotKit 原生工具调用"
- `docs/drafts/frontend-confirmation-dialog-plan.md` — 标记为废弃，添加指向本文档的链接

---

## 5. 风险评估

### 5.1 技术风险

| 风险 | 可能性 | 影响 | 缓解措施 |
|------|--------|------|----------|
| CopilotKit `useCopilotAction` API 与现有代码不兼容 | 中 | 高 | 先在独立分支测试，确认后再合并 |
| **工具名冲突**：前端 `httpRequest` 与后端 `@Tool` `httpRequest` 同名，LLM 可能调用后端版本（无确认） | 中 | 高 | 前端工具描述明确提及"暂停确认"等特性引导 LLM；Phase 3 移除后端版本彻底消除冲突 |
| `renderAndWaitForResponse` 在流式过程中行为不一致 | 低 | 中 | 验证 CopilotKit 测试用例中的 render 行为 |
| LLM 仍然输出 http-request 代码块（提示词未生效） | 中 | 中 | 保留旧实现作为降级方案 |
| 前端工具执行 HTTP 请求时 CORS 问题 | 低 | 高 | 后端已配置 CORS（`@CrossOrigin(origins = "*")`） |

### 5.2 功能风险

| 风险 | 可能性 | 影响 | 缓解措施 |
|------|--------|------|----------|
| 用户 auth token 过期后无法自动刷新 | 中 | 中 | 提示用户重新登录 |
| 复杂请求（文件上传等）不支持 | 低 | 低 | 当前方案仅支持 JSON 请求 |
| 并发请求（多个工具同时调用） | 低 | 中 | `useCopilotAction` 本身支持并发 |

### 5.3 回归风险

| 风险 | 可能性 | 影响 | 缓解措施 |
|------|--------|------|----------|
| 现有 http-request 功能被破坏 | 高 | 高 | 保留旧实现，支持渐进切换 |
| RAG/知识库功能受影响 | 低 | 高 | 执行现有测试用例 |
| 流式响应功能受影响 | 低 | 高 | 执行现有测试用例 |

---

## 6. 测试计划

### 6.1 单元测试

| 测试项 | 测试文件 | 验证点 |
|--------|----------|--------|
| `useHttpRequestTool` | `frontend/hooks/useHttpRequestTool.test.tsx` | Hook 注册成功 |
| `executeHttpRequest` | `frontend/hooks/executeHttpRequest.test.ts` | HTTP 请求正确执行 |
| `HttpRequestParams` Schema | `frontend/hooks/paramsSchema.test.ts` | Schema 验证正确 |

### 6.2 集成测试

| 测试项 | 测试命令 | 验证点 |
|--------|----------|--------|
| 工具调用流程 | `bash test.sh` | httpRequest 工具正确调用 |
| 确认对话框 | `bash test-agui-jwt-full.sh` | 确认对话框正确显示 |
| 流式响应 | `bash test-streaming.sh` | 流式响应正常工作 |

### 6.3 E2E 测试

| 测试场景 | 验证点 |
|----------|--------|
| 用户登录 → 搜索商品 | GET 请求无确认对话框 |
| 用户登录 → 添加购物车 | POST 请求显示确认对话框 |
| 用户确认 → 请求执行 | 结果正确显示 |
| 用户取消 → 操作取消 | 取消消息正确显示 |

---

## 7. 渐进式迁移策略

### 7.1 迁移阶段说明

```
Phase 1: 新工具注册 + 后端工具保留（降级方案）
┌─────────────────────────────────────────────────────────────┐
│  前端：useHttpRequestTool 注册到 CopilotKit                  │
│  后端：buildHttpRequest 工具仍然保留                        │
│  LLM：尝试调用 httpRequest 工具（前端工具）                  │
│       如果失败（如工具未注册），降级到 buildHttpRequest     │
└─────────────────────────────────────────────────────────────┘

Phase 2: 后端提示词更新 + 新工具为主
┌─────────────────────────────────────────────────────────────┐
│  前端：useHttpRequestTool 唯一注册                          │
│  后端：提示词更新，LLM 主动调用 httpRequest 工具            │
│  旧方案：如果 LLM 仍输出 http-request 代码块，仍可处理      │
└─────────────────────────────────────────────────────────────┘

Phase 3: 移除旧方案
┌─────────────────────────────────────────────────────────────┐
│  前端：useHttpRequestTool 唯一注册                          │
│  后端：移除 buildHttpRequest 工具                           │
│  清理：移除 AssistantMessage prop 注入和 extractHttpRequestMeta │
└─────────────────────────────────────────────────────────────┘
```

### 7.2 Feature Flag（可选）

如果需要更细粒度的控制，可以使用环境变量：

```typescript
// 在 useHttpRequestTool 内部使用环境变量控制
export function useHttpRequestTool() {
  // 注意：环境变量在构建时内联，不能作为运行时开关。此处仅作示例
  const isEnabled = process.env.NEXT_PUBLIC_ENABLE_NATIVE_TOOL_CALL !== 'false';

  useCopilotAction({
    name: "httpRequest",
    description: isEnabled
      ? "发送 HTTP 请求调用 REST API..."
      : "",  // 禁用时不提供描述，LLM 不会优先调用
    parameters: HttpRequestParams,
    renderAndWaitForResponse: isEnabled ? ({ args, status, respond }) => {
      // ... 现有逻辑
    } : undefined,
  });
}
```

> **注意**：Feature Flag 是可选的。更简单的做法是通过 `process.env.NEXT_PUBLIC_ENABLE_NATIVE_TOOL_CALL` 在构建时控制，或直接依赖提示词引导 LLM。**禁止在 Hook 调用外层使用条件判断**，这违反 React Rules of Hooks。

---

## 8. 预估工作量

| 阶段 | 任务 | 预估时间 |
|------|------|----------|
| Phase 1 | 创建 `useHttpRequestTool` Hook | 2-3 小时 |
| Phase 1 | 简化 `CopilotAssistantMessage` | 0.5 小时 |
| Phase 2 | 集成到 `page.tsx` | 1 小时 |
| Phase 2 | 修改后端提示词 | 0.5 小时 |
| Phase 3 | 代码清理 | 1 小时 |
| 测试 | 集成测试 + E2E 测试 | 2-3 小时 |
| **总计** | | **7-9 小时** |

---

## 9. 附录

### 9.1 CopilotKit `useCopilotAction` API 参考

**`useCopilotAction` 签名**（来自 `@copilotkit/react-core`）：
```typescript
function useCopilotAction(action: FrontendAction<T>, dependencies?: any[])
```

**重要约束**：`render` 和 `renderAndWaitForResponse` 是**互斥**的：
- `render` + `handler`：用于自动执行的工具
- `renderAndWaitForResponse`：用于需要用户交互确认的工具

### 9.2 `useCopilotAction` vs `useFrontendTool`

| Hook | 来源 | 用途 |
|------|------|------|
| `useCopilotAction` | `@copilotkit/react-core` | 注册前端工具，支持 `render`/`renderAndWaitForResponse` |
| `useFrontendTool` | `@copilotkit/react-core`（封装） | `useCopilotAction` 的封装，参数格式不同 |

**注意**：实际使用的是 `useCopilotAction`（直接使用 v1 兼容 API）。

### 9.3 相关文件清单

**新增文件**：
- `frontend/hooks/useHttpRequestTool.ts`

**修改文件**：
- `frontend/app/page.tsx`
- `frontend/components/CopilotAssistantMessage.tsx`
- `src/main/resources/prompts/skills-advisor/mode-rules.template`

**可选删除文件**：
- `frontend/components/ConfirmDialogContainer.tsx`（如完全迁移到新方案）
- `frontend/hooks/useHttpRequestTool.ts`（如回退）

---

## 10. 验证与调试指南

### 10.1 运行时验证清单

每完成一个 Phase，按以下清单验证：

**Phase 1 完成验证**：
- [ ] `frontend/hooks/useHttpRequestTool.ts` 文件存在且 `npx tsc --noEmit` 无错误
- [ ] `CopilotAssistantMessage.tsx` 已简化为纯透传

**Phase 2 完成验证**：
- [ ] 前端页面正常渲染（`http://localhost:4000`）
- [ ] 浏览器 Console 中 `[executeHttpRequest]` 日志可见（执行 HTTP 请求时）
- [ ] 后端日志中 `[buildHttpRequest]` 调用减少或消失
- [ ] `bash test-agui-jwt-full.sh` 测试通过
- [ ] 表格渲染正常（markdownTagRenderers 保留确认）

**Phase 3 完成验证**：
- [ ] `page.tsx` 中已无 `extractHttpRequestMeta`
- [ ] `ConfirmDialogContainer.tsx` 已删除
- [ ] `bash test-streaming.sh` 流式测试通过
- [ ] `bash test.sh` 通用 E2E 测试通过

### 10.2 调试技巧

**前端工具未注册**：
```bash
# 在浏览器 Console 中检查
# CopilotKit 会自动注册 useCopilotAction 工具，确认无报错
```

**LLM 仍然输出 http-request 代码块**：
```bash
# 检查后端日志中的系统提示词
# 搜索 "完整系统提示词" 确认 mode-rules 已更新
grep "系统提示词" /path/to/spring-boot.log | tail -5
```

**HTTP 请求 404（URL 错误）**：
```bash
# 检查 executeHttpRequest 发送的 URL
# 在浏览器 Console 中搜索 "[executeHttpRequest]"
# 确认 JAVA_BACKEND_BASE 正确（默认为 http://localhost:8080）
```

**工具名冲突导致无确认**：
```bash
# 检查后端日志确认 LLM 调用的是哪个 httpRequest
# 搜索 "STEP4" 日志（来自 extratJwt），如果出现说明调用了后端版本
grep "STEP4" /path/to/spring-boot.log
```

**CORS 错误**：
```bash
# 确认 AgUiController 有 @CrossOrigin(origins = "*")
# 同时确认目标 API Controller（如 ProductController）也有 CORS 配置
grep -r "CrossOrigin\|CorsConfiguration" src/main/java/
```

### 10.3 关键日志位置

| 日志 | 文件/位置 | 含义 |
|------|----------|------|
| `[SkillsAdvisor] 注入系统提示` | 后端启动日志 | 系统提示词注入成功 |
| `[STEP2] 收到 AG-UI 请求` | AgUiController | CopilotKit Runtime 已连接 |
| `[buildHttpRequest] 被调用` | SkillTools.java | LLM 仍在用旧工具（应减少） |
| `[executeHttpRequest] Failed to parse params JSON` | 前端 Console | params 参数格式错误 |
| `[STEP4] extractJwt` | SkillTools.java | 后端 HTTP 工具被调用（应消失） |

---

## 11. Git 工作流与回滚方案

### 11.1 分支策略

```bash
# 1. 从 master 创建重构分支
git checkout master
git pull origin master
git checkout -b refactor/copilotkit-native-tool-call

# 2. 每个 Phase 完成后提交
git add <modified-files>
git commit -m "feat: Phase 1 - add useHttpRequestTool hook
> - Create frontend/hooks/useHttpRequestTool.ts
> - Simplify CopilotAssistantMessage to pass-through"

# 3. 推送到远程（每个 Phase 可单独推送）
git push -u origin refactor/copilotkit-native-tool-call
```

### 11.2 推荐提交粒度

| 提交 | 内容 | 验证方式 |
|------|------|----------|
| Commit 1 | Phase 1：创建 `useHttpRequestTool.ts` + 简化 `CopilotAssistantMessage.tsx` | `npx tsc --noEmit` |
| Commit 2 | Phase 2：集成到 `page.tsx` + 修改 `mode-rules.template` | `bash test-agui-jwt-full.sh` |
| Commit 3 | Phase 3：清理旧代码 + 删除 `ConfirmDialogContainer.tsx` | `bash test-streaming.sh` + `bash test.sh` |
| Commit 4 | Phase 3 可选：移除后端工具 + 更新文档 | `mvn compile` |

### 11.3 回滚流程

**按阶段回滚（如某 Phase 出问题）**：

```bash
# 回滚到 Phase 1 前的状态
git stash && git checkout master

# 或只回滚特定文件
git checkout master -- frontend/app/page.tsx                    # 回滚 page.tsx
git checkout master -- frontend/components/CopilotAssistantMessage.tsx  # 回滚 CopilotAssistantMessage
git checkout master -- src/main/resources/prompts/skills-advisor/mode-rules.template  # 回滚提示词

# 删除新增文件
rm frontend/hooks/useHttpRequestTool.ts
```

**完整回滚（放弃所有重构改动）**：
```bash
git checkout master
git branch -D refactor/copilotkit-native-tool-call
# 重启服务验证
```

### 11.4 合并到 Master

```bash
# 在重构分支上所有 Phase 完成后
git checkout master
git merge refactor/copilotkit-native-tool-call

# 如果有冲突（通常不会，因为不修改共享文件的其他部分）
# 手动解决冲突后
git add .
git commit -m "merge: integrate CopilotKit native tool call refactoring"
git push origin master
```

---
