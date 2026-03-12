# 前端确认对话框功能实现规划

## 1. 问题背景

### 1.1 需求描述
在 CopilotKit 前端实现"用户确认/前端执行 API 请求"模式：
- 当 AI Agent 需要执行非 GET 请求（POST、PUT、DELETE 等）时
- 向用户展示确认对话框
- 用户确认后，在**前端**执行实际的 HTTP 请求
- 将结果返回给 Agent 继续处理

### 1.2 之前遇到的问题
在之前的实现尝试中，添加 `useCopilotAction` 后出现了 ZodError：
```
Agent execution failed: Error [ZodError]: [
  {
    "code": "invalid_type",
    "expected": "string",
    "received": "undefined",
    "path": ["message"],
    "message": "Required"
  }
]
```

### 1.3 当前状态
- **后端**：正常运行，测试脚本验证通过
- **前端**：基本功能正常（查询商品等 GET 请求可以正常工作）
- **stash**：包含之前尝试的修改（`useCopilotAction` + `renderAndWaitForResponse`）

---

## 2. 调查结果

### 2.1 `useCopilotAction` 支持情况

**结论：`renderAndWaitForResponse` 在 CopilotKit v1.53.0 中被完全支持。**

#### 类型定义（来自 `@copilotkit/react-core/dist/index.d.mts`）

```typescript
type FrontendAction<T extends Parameter[] | [] = [], N extends string = string> = Action<T> & {
  name: Exclude<N, "*">;
  disabled?: boolean;
  available?: FrontendActionAvailability;
  pairedAction?: string;
  followUp?: boolean;
} & ({
  render?: string | ((props: ActionRenderProps<T>) => ReactElement);
  renderAndWaitForResponse?: never;
  handler?: never;
} | {
  render?: never;
  renderAndWaitForResponse?: (props: ActionRenderPropsWait<T>) => ReactElement;
  handler?: never;
});
```

#### `renderAndWaitForResponse` 的 Props 类型

```typescript
interface ExecutingStateWait<T> {
  status: "executing";
  args: MappedParameterTypes<T>;
  respond: (result: any) => void;  // 关键：调用此函数返回结果给 AI
  result: undefined;
}

interface InProgressStateWait<T> {
  status: "inProgress";
  args: Partial<MappedParameterTypes<T>>;
  respond: undefined;
  result: undefined;
}

interface CompleteStateWait<T> {
  status: "complete";
  args: MappedParameterTypes<T>;
  respond: undefined;
  result: any;
}

type ActionRenderPropsWait<T> =
  | CompleteStateWait<T>
  | ExecutingStateWait<T>
  | InProgressStateWait<T>;
```

### 2.2 ZodError 根本原因分析

#### 错误来源
错误发生在 `@ag-ui/core/dist/events.ts` 的 `RunErrorEventSchema` 验证：

```typescript
export const RunErrorEventSchema = BaseEventSchema.extend({
  type: z.literal(EventType.RUN_ERROR),
  message: z.string(),  // ← 必填字段，不能是 undefined
  code: z.string().optional(),
});
```

#### 触发条件
1. 后端返回了一个 `RUN_ERROR` 事件
2. 但该事件的 `message` 字段是 `undefined`
3. 前端验证时发现必填字段缺失，抛出 ZodError

#### 根本原因推测
**不是 `instructions` 属性本身的问题**，而是：
1. 添加 `useCopilotAction` 后，CopilotKit Runtime 的请求处理流程发生了变化
2. 某些边界情况下，后端返回了格式不正确的错误事件
3. 前端在解析错误事件时触发了二次错误

### 2.3 HttpAgent 请求格式

HttpAgent 发送的请求体结构（`RunAgentInput`）：

```typescript
interface RunAgentInput {
  threadId: string;
  runId: string;
  parentRunId?: string;
  state: any;
  messages: Message[];  // 消息数组，不是单个 message 字符串
  tools?: Tool[];
  context?: Context[];
  forwardedProps?: any;
}
```

### 2.4 当前工作的版本分析

当前工作的 `page.tsx`（commit d7d15c9）：
- 使用 `CopilotPopup` 组件
- 有 `instructions` 属性（简单字符串）
- 有 `markdownComponents` 属性
- **没有** `useCopilotAction` hook
- **没有** `renderAndWaitForResponse`

---

## 3. 问题根源深度分析

### 3.1 为什么添加 `useCopilotAction` 后出错？

经过分析，可能的原因有：

#### 假设 A：`markdownTagRenderers` 属性名问题
- Stash 中将 `markdownComponents` 改成了 `markdownTagRenderers`
- 但当前工作版本使用的是 `markdownComponents`
- 可能存在兼容性问题

**验证方法**：检查 CopilotKit v1.53.0 支持哪个属性名

**调查结果**：
- `markdownTagRenderers` 是 **正确的属性名**（CopilotKit v1.53.0）
- `markdownComponents` 可能是旧版本 API 或被忽略

#### 假设 B：`instructions` 内容中的换行/特殊字符
- Stash 中 `instructions` 是多行字符串，包含反引号模板
- 可能导致解析问题

#### 假设 C：CopilotKit 与 AG-UI 协议版本不匹配
- CopilotKit v1.53.0 内部使用 `@copilotkitnext/runtime`
- 后端 ag-ui-4j 可能与某些新特性不兼容

### 3.2 关键发现

通过对比分析发现：
1. **当前工作版本** 的 `page.tsx` 已经有 `instructions` 属性，但使用的是 `markdownComponents`
2. **Stash 版本** 同时修改了多个内容：
   - 添加了 `useCopilotAction`
   - 将 `markdownComponents` 改为 `markdownTagRenderers`
   - 修改了 `instructions` 内容

**问题**：无法确定是哪个修改导致的问题！

---

## 4. 解决方案

### 4.1 策略：增量修改 + 分步验证

采用**最小修改原则**，每次只改一个点，逐步验证：

#### 步骤 1：验证 `markdownTagRenderers` 兼容性
- 在**不添加** `useCopilotAction` 的情况下
- 仅将 `markdownComponents` 改为 `markdownTagRenderers`
- 验证基本功能是否正常

#### 步骤 2：添加空的 `useCopilotAction`
- 在确认步骤 1 正常后
- 添加一个简单的、没有实际逻辑的 `useCopilotAction`
- 验证是否触发错误

#### 步骤 3：实现完整的确认对话框
- 在确认步骤 2 正常后
- 实现完整的 `renderAndWaitForResponse` 逻辑

### 4.2 备选方案

如果 `useCopilotAction` 在当前架构下无法正常工作，考虑：

#### 方案 B：后端确认模式
- 保持当前架构不变
- 在后端实现确认逻辑
- 通过 SSE 事件向前端发送确认请求

#### 方案 C：使用 CopilotChat 替代 CopilotPopup
- `CopilotChat` 可能有更好的 action 支持
- 需要调研 API 差异

---

## 5. 详细实施计划

### 第一阶段：诊断与验证（不修改功能代码）

#### 任务 1.1：确认当前工作版本
```bash
# 确认当前代码状态
cd frontend && git status
git diff HEAD -- app/page.tsx
```

**预期结果**：无差异（当前代码与 HEAD 一致）

#### 任务 1.2：测试 `markdownTagRenderers` 兼容性
- 仅修改 `markdownComponents` → `markdownTagRenderers`
- **不添加** `useCopilotAction`
- 使用 Playwright 测试基本功能

**预期结果**：
- 如果成功：继续步骤 2
- 如果失败：问题在属性名不兼容，需要使用 `markdownComponents`

#### 任务 1.3：检查 CopilotPopup 类型定义
- 确认 v1.53.0 支持哪些属性
- 确认 `markdownTagRenderers` 的正确函数签名

### 第二阶段：实现确认对话框功能

#### 任务 2.1：添加最小化的 `useCopilotAction`
```typescript
// 先添加一个空的 action，验证不会出错
useCopilotAction({
  name: "testAction",
  description: "Test action for validation",
  parameters: [],
  renderAndWaitForResponse: ({ status, respond }) => {
    return <div>Test Action (status: {status})</div>;
  },
});
```

**预期结果**：
- 如果成功：说明 `useCopilotAction` 本身没问题
- 如果失败：需要深入调试请求流程

#### 任务 2.2：实现完整的确认对话框 Action

在确认任务 2.1 成功后，实现完整的 `confirmHttpRequest` action：

```typescript
useCopilotAction({
  name: "confirmHttpRequest",
  description: "当需要执行 HTTP 请求（特别是 POST、PUT、DELETE 等修改操作）时使用",
  parameters: [
    { name: "description", type: "string", description: "操作描述", required: true },
    { name: "method", type: "string", description: "HTTP 方法", required: true },
    { name: "url", type: "string", description: "请求 URL", required: true },
    { name: "params", type: "object", description: "查询参数", required: false },
    { name: "body", type: "object", description: "请求体", required: false },
  ],
  renderAndWaitForResponse: ({ status, args, respond }) => {
    if (status === "inProgress") {
      return <div>准备确认...</div>;
    }

    // 状态为 "executing" 时显示确认对话框
    return (
      <ConfirmDialog
        description={args.description as string}
        requestMeta={{
          method: args.method as string,
          url: args.url as string,
          params: args.params as Record<string, string>,
          body: args.body,
        }}
        onConfirm={async () => {
          const result = await executeHttpRequest({...});
          respond({ confirmed: true, result });
        }}
        onCancel={() => {
          respond({ confirmed: false, error: "用户取消" });
        }}
      />
    );
  },
});
```

#### 任务 2.3：更新 `instructions` 内容
- 在 `CopilotPopup` 的 `instructions` 中添加使用 `confirmHttpRequest` 的说明
- 指导 AI 在什么情况下使用这个 action

### 第三阶段：测试验证

#### 任务 3.1：单元测试
- 测试确认对话框组件渲染
- 测试 `respond` 函数被正确调用

#### 任务 3.2：集成测试（Playwright）
1. 打开聊天对话框
2. 发送一个需要确认的请求（如"添加商品到购物车"）
3. 验证确认对话框出现
4. 点击确认/取消
5. 验证结果正确返回

---

## 6. 风险评估

### 6.1 已识别风险

| 风险 | 影响 | 可能性 | 缓解措施 |
|------|------|--------|----------|
| `useCopilotAction` 与 HttpAgent 不兼容 | 高 | 中 | 分步验证，准备备选方案 |
| `markdownTagRenderers` 不兼容 | 中 | 低 | 单独测试，保持原属性名 |
| 后端返回格式不兼容 | 高 | 低 | 添加错误处理，记录详细日志 |
| CopilotKit 版本问题 | 高 | 低 | 锁定版本，记录依赖 |

### 6.2 回滚计划
- 保留 stash 中的修改
- 每个步骤前创建 git commit
- 出现问题时可立即回滚

---

## 7. 成功标准

### 7.1 功能标准
- [ ] GET 请求正常工作（查询商品等）
- [ ] POST/PUT/DELETE 请求触发确认对话框
- [ ] 用户确认后，前端执行请求并返回结果
- [ ] 用户取消后，返回取消信息给 AI
- [ ] Markdown 表格正确渲染

### 7.2 技术标准
- [ ] 无 ZodError 或其他验证错误
- [ ] 无控制台错误
- [ ] 请求响应时间 < 30 秒

---

## 8. 时间估算

| 阶段 | 任务 | 预计时间 |
|------|------|----------|
| 诊断 | 验证 markdownTagRenderers | 15 分钟 |
| 诊断 | 测试 useCopilotAction | 15 分钟 |
| 实现 | 完整确认对话框功能 | 30 分钟 |
| 测试 | Playwright 集成测试 | 20 分钟 |
| **总计** | | **80 分钟** |

---

## 9. 待确认问题

在开始实施前，需要确认：

1. **是否接受分步验证策略？** - 这样可以精确定位问题
2. **如果 `useCopilotAction` 无法工作，是否接受备选方案？** - 后端确认模式
3. **测试范围** - 是否需要覆盖所有 HTTP 方法（POST/PUT/DELETE/PATCH）？

---

## 10. 附录

### A. 相关文件路径

```
frontend/
├── app/
│   ├── page.tsx                    # 主页面组件
│   └── api/copilotkit/
│       └── route.ts                # CopilotKit BFF 层
├── components/
│   └── MarkdownRenderer.tsx       # （已删除）
└── node_modules/
    ├── @copilotkit/
    │   ├── react-core/             # useCopilotAction 定义
    │   ├── react-ui/               # CopilotPopup 组件
    │   └── runtime/                # CopilotRuntime
    └── @ag-ui/
        ├── client/                 # HttpAgent
        └── core/                   # RunAgentInput, Events
```

### B. 版本信息

```json
{
  "@ag-ui/client": "^0.0.47",
  "@copilotkit/react-core": "1.53.0",
  "@copilotkit/react-ui": "1.53.0",
  "@copilotkit/runtime": "1.53.0"
}
```

### C. 参考文档

- CopilotKit Actions: https://docs.copilotkit.ai/reference/components/action
- AG-UI Protocol: https://github.com/ag-ui-protocol/ag-ui
- React Markdown: https://github.com/remarkjs/react-markdown

---

## 4. 实施方案

### 4.1 方案选择

经过分析，推荐**方案 B（最小改动方案）**，原因：
1. 保留现有工作代码的 `markdownComponents` 属性
2. 只添加必要的 `useCopilotAction` 代码
3. 风险最小，易于回滚

### 4.2 具体实施步骤

#### 步骤 1：添加 imports 和类型定义

```typescript
// 在文件顶部添加
import { useCopilotAction } from "@copilotkit/react-core";
import { useState, useCallback } from "react";

// 添加接口定义（在文件顶部，Home 函数之前）
interface HttpRequestMeta {
  method: string;
  url: string;
  params?: Record<string, string>;
  headers?: Record<string, string>;
  body?: any;
}
```

#### 步骤 2：添加辅助函数

在 `FeatureCard` 函数之前添加：

```typescript
// 执行 HTTP 请求
async function executeHttpRequest(meta: HttpRequestMeta): Promise<string> {
  let url = meta.url;

  if (meta.params && Object.keys(meta.params).length > 0) {
    const sp = new URLSearchParams(meta.params);
    url += '?' + sp.toString();
  }

  const opts: RequestInit = {
    method: meta.method,
    headers: { 'Content-Type': 'application/json' }
  };

  if (meta.body) {
    opts.body = JSON.stringify(meta.body);
  }

  const resp = await fetch(url, opts);
  const responseText = await resp.text();

  // 调用解释 API（可选）
  try {
    const explainResp = await fetch('/api/explain-result', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        method: meta.method,
        url: meta.url,
        queryParams: meta.params || {},
        statusCode: resp.status,
        responseBody: responseText
      })
    });
    if (explainResp.ok) {
      return await explainResp.text();
    }
  } catch (e) {
    console.error('解释结果失败:', e);
  }

  return responseText;
}
```

#### 步骤 3：添加确认对话框组件

```typescript
function ConfirmDialog({
  description,
  requestMeta,
  onConfirm,
  onCancel,
  isLoading
}: {
  description: string;
  requestMeta: HttpRequestMeta;
  onConfirm: () => void;
  onCancel: () => void;
  isLoading: boolean;
}) {
  return (
    <div className="bg-white dark:bg-gray-800 rounded-lg shadow-lg p-4 my-2 border border-orange-200 dark:border-orange-800">
      <div className="flex items-start gap-2 mb-3">
        <span className="text-2xl">⚠️</span>
        <div className="flex-1">
          <h4 className="font-semibold text-orange-600 dark:text-orange-400 mb-2">确认执行操作</h4>
          <p className="text-gray-700 dark:text-gray-300 text-sm whitespace-pre-wrap">{description}</p>
        </div>
      </div>

      <div className="bg-gray-50 dark:bg-gray-700 rounded p-2 mb-3 text-xs font-mono">
        <div className="flex gap-2">
          <span className="bg-blue-100 dark:bg-blue-800 text-blue-800 dark:text-blue-200 px-2 py-0.5 rounded font-bold">
            {requestMeta.method}
          </span>
          <span className="text-gray-600 dark:text-gray-300">{requestMeta.url}</span>
        </div>
        {requestMeta.body && (
          <pre className="mt-2 text-gray-600 dark:text-gray-400 overflow-x-auto max-h-32">
            {JSON.stringify(requestMeta.body, null, 2)}
          </pre>
        )}
      </div>

      <div className="flex gap-2 justify-end">
        <button
          onClick={onCancel}
          disabled={isLoading}
          className="px-4 py-2 text-sm font-medium text-gray-700 dark:text-gray-300 bg-gray-100 dark:bg-gray-600 rounded-lg hover:bg-gray-200 dark:hover:bg-gray-500 disabled:opacity-50"
        >
          取消
        </button>
        <button
          onClick={onConfirm}
          disabled={isLoading}
          className="px-4 py-2 text-sm font-medium text-white bg-orange-500 rounded-lg hover:bg-orange-600 disabled:opacity-50 flex items-center gap-2"
        >
          {isLoading ? (
            <>
              <span className="animate-spin">⏳</span>
              执行中...
            </>
          ) : (
            '确认执行'
          )}
        </button>
      </div>
    </div>
  );
}
```

#### 步骤 4：在 Home 组件中添加 useCopilotAction

在 `export default function Home()` 内部，`return` 语句之前添加：

```typescript
// 注册确认 HTTP 请求的 Action
useCopilotAction({
  name: "confirmHttpRequest",
  description: "当需要执行 HTTP 请求（特别是 POST、PUT、DELETE 等修改操作）时，向用户展示确认对话框。",
  parameters: [
    {
      name: "description",
      type: "string",
      description: "用自然语言描述将要执行的操作",
      required: true,
    },
    {
      name: "method",
      type: "string",
      description: "HTTP 方法：POST、PUT、DELETE 等",
      required: true,
    },
    {
      name: "url",
      type: "string",
      description: "请求的 URL 路径",
      required: true,
    },
    {
      name: "params",
      type: "object",
      description: "查询参数（可选）",
      required: false,
    },
    {
      name: "body",
      type: "object",
      description: "请求体（可选）",
      required: false,
    },
  ],
  renderAndWaitForResponse: ({ status, args, respond }) => {
    // inProgress 状态
    if (status === "inProgress") {
      return (
        <div className="flex items-center gap-2 text-gray-500 p-4">
          <span className="animate-spin">⏳</span>
          <span>正在准备确认请求...</span>
        </div>
      );
    }

    // executing 状态 - 显示确认对话框
    const [isLoading, setIsLoading] = useState(false);

    const handleConfirm = useCallback(async () => {
      setIsLoading(true);
      try {
        const result = await executeHttpRequest({
          method: args.method as string,
          url: args.url as string,
          params: args.params as Record<string, string> | undefined,
          body: args.body,
        });
        respond!({ confirmed: true, result });
      } catch (error: any) {
        respond!({ confirmed: false, error: error.message });
      }
    }, [args, respond]);

    const handleCancel = useCallback(() => {
      respond!({ confirmed: false, error: "用户取消了操作" });
    }, [respond]);

    return (
      <ConfirmDialog
        description={args.description as string}
        requestMeta={{
          method: args.method as string,
          url: args.url as string,
          params: args.params as Record<string, string> | undefined,
          body: args.body,
        }}
        onConfirm={handleConfirm}
        onCancel={handleCancel}
        isLoading={isLoading}
      />
    );
  },
});
```

### 4.3 关键注意事项

#### ⚠️ 重要：状态管理问题

上述代码有一个 React Hooks 问题：在 `renderAndWaitForResponse` 内部使用 `useState` 是不正确的，因为这是一个渲染函数，每次调用都会重新创建状态。

**正确的做法**：使用容器组件模式。

```typescript
// 正确的实现
renderAndWaitForResponse: ({ status, args, respond }) => {
  if (status === "inProgress") {
    return <div>准备中...</div>;
  }

  return (
    <ConfirmDialogContainer
      description={args.description as string}
      requestMeta={{
        method: args.method as string,
        url: args.url as string,
        params: args.params as Record<string, string> | undefined,
        body: args.body,
      }}
      respond={respond!}
    />
  );
},

// 单独的容器组件
function ConfirmDialogContainer({
  description,
  requestMeta,
  respond,
}: {
  description: string;
  requestMeta: HttpRequestMeta;
  respond: (result: any) => void;
}) {
  const [isLoading, setIsLoading] = useState(false);

  const handleConfirm = useCallback(async () => {
    setIsLoading(true);
    try {
      const result = await executeHttpRequest(requestMeta);
      respond({ confirmed: true, result });
    } catch (error: any) {
      respond({ confirmed: false, error: error.message });
    }
  }, [requestMeta, respond]);

  const handleCancel = useCallback(() => {
    respond({ confirmed: false, error: "用户取消了操作" });
  }, [respond]);

  return (
    <ConfirmDialog
      description={description}
      requestMeta={requestMeta}
      onConfirm={handleConfirm}
      onCancel={handleCancel}
      isLoading={isLoading}
    />
  );
}
```

### 4.4 测试计划

1. **基本功能测试**：确认 GET 请求仍然正常工作
2. **确认对话框显示测试**：发送一个 POST 请求，检查对话框是否出现
3. **确认/取消操作测试**：
   - 点击确认，检查请求是否执行，结果是否返回给 AI
   - 点击取消，检查是否正确报告取消状态
4. **错误处理测试**：模拟请求失败，检查错误信息是否正确传递

---

## 5. 风险评估

| 风险 | 可能性 | 影响 | 缓解措施 |
|------|--------|------|----------|
| ZodError 再次出现 | 中 | 高 | 使用容器组件模式，避免在渲染函数中使用 hooks |
| 与现有功能冲突 | 低 | 中 | 保持现有 `markdownComponents` 不变 |
| 后端不响应 action | 中 | 中 | 确保 action 名称正确，参数描述清晰 |
| 请求执行失败 | 低 | 低 | 在代码中添加错误处理和降级逻辑 |

---

## 6. 回滚计划

如果实施后出现问题：

1. **立即回滚**：
   ```bash
   git checkout HEAD -- frontend/app/page.tsx
   ```

2. **重启前端服务**：
   ```bash
   # 杀掉旧进程并重启
   cd frontend && npm run dev
   ```

---

## 11. 用户审查反馈与补充

### 11.1 问题分析的进一步确认

> **工作假设**：当前没有确凿证据证明 `useCopilotAction` 与 HttpAgent/AG-UI 协议完全兼容，因此所有实现都按**实验性质**推进，并通过日志捕获完整事件 payload。

### 11.2 CopilotKit API 使用约束

#### 不使用 `pairedAction`
根据社区反馈，添加 `pairedAction` 会导致 action 不触发。本实现**不使用** `pairedAction` 属性。
- 参考：https://github.com/CopilotKit/CopilotKit/issues/1455

#### `respond` 函数调用时机
- **只在 `status === "executing"` 时调用 `respond`**
- 在 `inProgress` 和 `complete` 状态**不调用** `respond`
- 这符合类型约束，避免运行时错误

### 11.3 API 边界与安全性

#### URL 路径约束
为防止 prompt injection 或误用，`executeHttpRequest` 将对 URL 进行约束：

```typescript
async function executeHttpRequest(meta: HttpRequestMeta): Promise<ActionResult> {
  let url = meta.url;

  // 安全约束：只允许相对路径或特定域名
  if (url.startsWith('http://') || url.startsWith('https://')) {
    // 只允许本地 API
    const allowedOrigins = ['http://localhost:8080', 'http://127.0.0.1:8080'];
    const urlObj = new URL(url);
    if (!allowedOrigins.includes(urlObj.origin)) {
      throw new Error(`不允许访问外部域名: ${urlObj.origin}`);
    }
  }
  // 相对路径直接使用（假设指向后端 API）
  // ... 其余逻辑
}
```

#### instructions 约束
在 `CopilotPopup` 的 `instructions` 中明确约束 agent：
```
使用 confirmHttpRequest 时，只允许对以下业务 API 路径操作：
- /api/products/*
- /api/orders/*
- /api/users/*
禁止跨域访问其他站点。
```

### 11.4 错误传递与统一 Schema

#### `respond` 返回对象的统一结构

```typescript
interface ActionResult {
  confirmed: boolean;
  status: 'success' | 'error' | 'cancelled';
  statusCode?: number;
  bodyText?: string;
  errorMessage?: string;  // 错误信息，确保有值
}

// 成功时
respond({
  confirmed: true,
  status: 'success',
  statusCode: 200,
  bodyText: result
});

// 用户取消时
respond({
  confirmed: false,
  status: 'cancelled',
  errorMessage: '用户取消了操作'
});

// 请求失败时
respond({
  confirmed: false,
  status: 'error',
  errorMessage: error.message || '未知错误'
});
```

#### 后端 Agent 错误处理
确保后端 agent 在处理工具结果时，任何抛出的错误都带 `message` 字段，避免再次触发 ZodError。

### 11.5 前端 RUN_ERROR 兜底处理

在 AG-UI 客户端添加错误事件兜底：

```typescript
// 在 route.ts 或适当位置
const runtime = new CopilotRuntime({
  agents: {
    default: new HttpAgent({
      url: `${JAVA_BACKEND_URL}/api/agui`,
      // 添加错误处理中间件（如果 API 支持）
    }),
  },
});
```

或者在 `page.tsx` 中捕获 SSE 错误：

```typescript
useEffect(() => {
  // 监听未捕获的 AG-UI 错误
  const handleError = (event: ErrorEvent) => {
    if (event.message?.includes('ZodError') || event.message?.includes('RUN_ERROR')) {
      console.error('[AG-UI Error]', event);
      // 可选：显示用户友好的错误提示
    }
  };
  window.addEventListener('error', handleError);
  return () => window.removeEventListener('error', handleError);
}, []);
```

### 11.6 可观测性增强

#### executeHttpRequest 返回结构化数据

```typescript
async function executeHttpRequest(meta: HttpRequestMeta): Promise<ActionResult> {
  // ... 执行请求

  const resp = await fetch(url, opts);
  const responseText = await resp.text();

  // 返回结构化结果，不只是自然语言
  const baseResult: ActionResult = {
    confirmed: true,
    status: resp.ok ? 'success' : 'error',
    statusCode: resp.status,
    bodyText: responseText,
  };

  // 可选：调用解释 API 生成友好描述
  if (resp.ok) {
    try {
      const explainResp = await fetch('/api/explain-result', { /* ... */ });
      if (explainResp.ok) {
        baseResult.bodyText = await explainResp.text();
      }
    } catch (e) {
      console.warn('解释结果失败，使用原始响应:', e);
    }
  } else {
    baseResult.errorMessage = `HTTP ${resp.status}: ${responseText}`;
  }

  return baseResult;
}
```

### 11.7 测试增强

#### Playwright 捕获控制台错误

```typescript
// 在测试文件中
test('确认对话框功能', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', msg => {
    if (msg.type() === 'error') {
      consoleErrors.push(msg.text());
    }
  });

  // ... 执行测试步骤

  // 验证没有 ZodError 或 React 警告
  expect(consoleErrors.some(e => e.includes('ZodError'))).toBe(false);
  expect(consoleErrors.some(e => e.includes('Warning:'))).toBe(false);
});
```

### 11.8 首轮上线范围限制

首轮实现只对以下明确的写接口开放 `confirmHttpRequest`：
- `POST /api/products/cart` - 添加购物车
- `POST /store/order` - 下单

待路径稳定后，再扩展到更多 POST/PUT/DELETE 操作。

### 11.9 调试日志

开发环境启用 CopilotKit/AG-UI 调试：

```typescript
// .env.local
NEXT_PUBLIC_COPILOT_DEBUG=true
```

并在浏览器控制台捕获完整事件流，便于分析 `RUN_ERROR` 结构。

---

**文档版本**: 2.0
**更新时间**: 2026-03-12
**状态**: 待批准（已根据用户反馈更新）
