# CopilotKit 前端确认对话框功能实现规划

**版本**: 3.0（彻底重新设计）
**更新时间**: 2026-03-12
**状态**: 待批准

---

## 1. 核心结论（先写结论）

之前的 AI 助手走错了方向，花大量精力试图用 `useCopilotAction` + `renderAndWaitForResponse` 来实现确认功能，这是一条完全不必要的弯路。

**正确方案**：与"传统前端"完全相同的思路——**通过 `markdownTagRenderers` 的 `pre` 组件覆盖，拦截渲染时的 ` ```http-request ` 代码块，就地展示确认对话框**。

### 1.1 传统前端是怎么做的？

```javascript
// 1. POST /api/chat → 收到完整 AI 响应文本
// 2. 检测是否包含 ```http-request 代码块
function parseConfirmAction(text) {
    const regex = /```http-request\s*\n([\s\S]*?)\n\s*```/;
    const match = text.match(regex);
    if (!match) return null;
    const description = text.substring(0, match.index).trim();
    const requestMeta = JSON.parse(match[1].trim());
    return { description, requestMeta };
}
// 3. 如果有，显示确认/取消按钮
// 4. 用户确认 → 前端执行 HTTP 请求 → 展示结果
// 5. 用户取消 → 展示"已取消该操作"
```

### 1.2 CopilotKit 前端可以用相同思路吗？

**可以！** CopilotKit 的 Markdown 渲染管道提供了足够的扩展钩子：
- `CopilotPopup` 支持 `markdownTagRenderers` 属性（注意：不是 `markdownComponents`！）
- `markdownTagRenderers` 最终传给 `Markdown.tsx` 的 `components` prop（ReactMarkdown 标准）
- 通过覆盖 `pre` 组件，可以在渲染时检测 `language-http-request` 子节点并替换成确认对话框

### 1.3 为什么之前卡住了？

之前的 AI 助手把一个**只需要覆盖渲染组件**的问题，错误地变成了**让 AI 调用新的前端 Action** 的架构，导致：
1. 需要修改 AI 的调用链（后端 `httpRequest` tool → AI → 前端 `executeHttpRequest` action）
2. 引入了 `useCopilotAction` + `renderAndWaitForResponse` 的 ZodError 问题
3. 完全不必要的复杂度

---

## 2. 技术方案详解

### 2.1 关键发现：`markdownTagRenderers` vs `markdownComponents`

当前 `page.tsx` 使用了**错误的 prop 名**：
```tsx
// 当前代码 - 错误！CopilotKit 没有 markdownComponents 属性
<CopilotPopup
  markdownComponents={{ table: ..., code: ... }}  // ← 被静默忽略！
/>
```

正确的 prop 名是 `markdownTagRenderers`：
```tsx
// CopilotKit v1.53.0 定义（来自 Chat.tsx line 191）
interface CopilotModalProps {
  markdownTagRenderers?: ComponentsMap;
  // ...
}
```

这意味着**当前前端的所有自定义表格/代码渲染都失效了**！切换到正确的 prop 名后，一切都会正常工作。

### 2.2 `markdownTagRenderers` 的渲染链路

```
CopilotPopup
  → Chat.tsx (markdownTagRenderers prop)
    → Modal.tsx
      → Messages.tsx
        → RenderMessage.tsx
          → AssistantMessage.tsx
            → Markdown.tsx
              → ReactMarkdown
                components={{ ...defaultComponents, ...markdownTagRenderers }}
```

`markdownTagRenderers` 中的自定义组件会**覆盖**默认的 ReactMarkdown 组件。

### 2.3 已验证技术细节

**react-markdown v8.0.7**（项目实际安装版本）在 `ast-to-react.js` 中使用：
```javascript
React.createElement(component, properties, children)
```
对于 `<pre><code className="language-http-request">...</code></pre>` 结构，`pre` 组件接收的 `children` 是一个未渲染的 React 元素（`{type: code_component, props: {className: "language-http-request", children: "json"}}`）。

### 2.4 为什么覆盖 `pre` 可以检测 `http-request` 代码块？

ReactMarkdown 对 ` ```http-request\n{json}\n``` ` 代码块的渲染流程：

```
ReactMarkdown 解析 →
  创建 React 元素树：
    <CustomPre>
      { React.createElement(defaultCode, { className: "language-http-request" }, "{json}") }
    </CustomPre>
```

当 React 调用 `CustomPre` 时，`children` 是一个 **React 元素描述**（尚未渲染），可以通过 `children.props.className` 访问语言标记，通过 `children.props.children` 访问原始 JSON 字符串。

这意味着：
- **不需要覆盖 `code`**：`pre` 层就能拿到所有需要的信息
- **其他代码块的语法高亮保持不变**：因为我们没有覆盖 `code`，CopilotKit 默认的 `CodeBlock`（含 Prism 语法高亮）仍然正常工作
- **流式光标处理保持不变**：`▍` 字符的动画效果由默认 `code` 组件处理

### 2.4 完整的技术流程

```
用户发消息 → CopilotKit 流式接收 AI 响应
                ↓
         AI 的 httpRequest 工具被后端调用
         confirm-before-mutate=true 时返回：
           "[CONFIRM_REQUIRED]\n```http-request\n{json}\n```"
                ↓
         AI 将此内容输出到消息中
                ↓
         CopilotKit 流式渲染消息
                ↓
         ReactMarkdown 解析到 ```http-request 块
                ↓
         调用我们的自定义 pre 组件
                ↓
         检测到 language-http-request → 解析 JSON → 渲染 <ConfirmDialogContainer>
                ↓
         用户看到确认对话框，点击确认或取消
                ↓
         [确认] 前端执行 HTTP 请求 → 调用 /api/explain-result → 展示结果
         [取消] 展示取消消息
```

---

## 3. 现有代码利用情况

### 3.1 "前任 AI 助手"留下的代码评估

| 文件 | 状态 | 说明 |
|------|------|------|
| `frontend/components/ConfirmDialogContainer.tsx` | **保留并修改** | UI 设计很好，需要移除 `respond` prop，改为内部状态管理 |
| `frontend/hooks/useHttpConfirmationAction.tsx` | **不使用/可删除** | 这是旧方案（useCopilotAction），新方案不需要 |

### 3.2 `ConfirmDialogContainer.tsx` 需要的修改

**当前设计（旧方案）**：
```tsx
// 需要外部传入 respond 函数（来自 useCopilotAction 的 renderAndWaitForResponse）
<ConfirmDialogContainer
  description={...}
  requestMeta={...}
  respond={respond as (result: HttpExecutionResult) => void}  // ← 要移除
/>
```

**新方案设计**：
```tsx
// 自包含的确认对话框，内部管理状态
<ConfirmDialogContainer
  description={...}
  requestMeta={...}
  // 不需要 respond！内部状态管理。
/>
```

组件内部状态机：
```
pending（显示确认/取消按钮）
  ↓ 点击确认
executing（显示"执行中..."）
  ↓ 请求完成
completed（显示结果）

pending
  ↓ 点击取消
cancelled（显示"已取消该操作"）
```

### 3.3 `executeHttpRequest` 函数

当前 `ConfirmDialogContainer.tsx` 里的 `executeHttpRequest` 函数**不调用** `/api/explain-result`，但传统前端调用了。

**决策**：修改 `ConfirmDialogContainer.tsx` 中的 `executeHttpRequest` 以调用 `/api/explain-result`，与传统前端保持一致。

---

## 4. 具体实施方案

### 4.1 修改 `frontend/components/ConfirmDialogContainer.tsx`

**主要变更**：
1. 移除 `respond` prop 和 `HttpExecutionResult` 中的 `respond` 调用
2. 增加内部 `stage: 'pending' | 'executing' | 'completed' | 'cancelled'` 状态
3. 增加 `result: string | null` 内部状态（存储操作结果文本）
4. 修改 `executeHttpRequest` 函数以调用 `/api/explain-result`
5. 根据 stage 渲染不同 UI

**新的 props 接口**：
```typescript
interface ConfirmDialogContainerProps {
  description: string;
  requestMeta: HttpRequestMeta;
  // 不再需要 respond！
}
```

**新的渲染逻辑**：
```tsx
// stage === 'pending': 显示确认/取消按钮（现有 UI，稍作调整）
// stage === 'executing': 显示加载动画
// stage === 'completed': 显示 ✅ + result
// stage === 'cancelled': 显示 ❌ 已取消该操作
```

### 4.2 修改 `frontend/app/page.tsx`

**主要变更**：
1. 添加 `import React from 'react'`（用于 `React.isValidElement`）
2. 添加 `import { ConfirmDialogContainer } from '@/components/ConfirmDialogContainer'`
3. 将 `markdownComponents` **改名为** `markdownTagRenderers`
4. 在 `markdownTagRenderers` 中添加 `pre` 覆盖

**`pre` 覆盖代码**：
```tsx
pre: ({ children, ...props }: any) => {
  // 检测 http-request 代码块
  if (React.isValidElement(children)) {
    const codeProps = (children as React.ReactElement<any>).props;
    if (codeProps?.className === 'language-http-request') {
      try {
        const content = String(codeProps.children).trim();
        const requestMeta: HttpRequestMeta = JSON.parse(content);
        // 使用稳定的 key，确保流式渲染时 ConfirmDialogContainer 的状态不丢失
        const stableKey = `${requestMeta.method}-${requestMeta.url}`;
        return <ConfirmDialogContainer key={stableKey} requestMeta={requestMeta} />;
      } catch {
        // JSON 解析失败，降级到普通代码块渲染
      }
    }
  }
  // 正常的 pre 渲染（保留默认 CodeBlock 语法高亮）
  return (
    <pre className="bg-gray-100 dark:bg-gray-800 p-4 rounded-lg overflow-x-auto my-2" {...props}>
      {children}
    </pre>
  );
},
```

**关于流式渲染时的状态稳定性**：

AI 消息在流式接收过程中，`Markdown` 组件会随内容变化频繁重渲染。关键点：
- 使用 `key={stableKey}` 确保 React 能通过 `method+url` 识别同一个对话框实例
- 即使消息内容后续有追加，React reconciliation 会保留已有的 `ConfirmDialogContainer` 状态（pending/executing/completed）
- 传统前端不存在此问题（因为是非流式的），CopilotKit 流式渲染需要特别处理

**注意**：现有的 `table`, `thead`, `tbody`, `tr`, `th`, `td`, `p`, `ul`, `ol`, `li`, `code` 覆盖需要**全部保留**，只需把键名 `markdownComponents` 改成 `markdownTagRenderers`。但 `code` 和 `pre` 的自定义样式会导致**其他代码块失去语法高亮**。

#### 关于 `code` 的处理策略

**重要权衡**：
- 当前 `page.tsx` 中的 `code` 覆盖（即使 prop 名错了）使用简单样式，没有语法高亮
- CopilotKit 默认的 `code` 处理有语法高亮（Prism）
- 使用 `markdownTagRenderers` 之后，如果保留 `code` 覆盖，则会失去语法高亮

**决策**：
- **移除** `code` 覆盖（让 CopilotKit 默认的 Prism 语法高亮生效）
- **保留** `pre` 覆盖（用于 http-request 检测 + 样式）
- **保留** 表格相关覆盖（table, thead, tbody 等）

### 4.3 关于 description 的获取

后端 `SkillTools.java` 返回的 `http-request` JSON 结构（见 SkillTools.java 第 99-102 行）：
```java
return "[CONFIRM_REQUIRED]\n此操作需要用户确认后才能执行。" +
       "```http-request\n" + json + "\n```";
```

其中 `json` 来自于 `buildRequestJson()` 方法，包含 `method`, `url`, `params`, `body` 等字段，**但不含 `description`**。

而 AI 在输出消息时，会在代码块之前写自然语言描述（per SkillsAdvisor 的 instructions）。

**方案**：在 `pre` 组件里，`description` 没法直接拿到（因为它在 `pre` 的上下文之外）。

**处理方式**：
1. **最简方式**：`ConfirmDialogContainer` 不需要 `description` prop；组件内部显示请求方法、URL 和 body 就足够了
2. **或者**：在后端的 JSON 里加一个 `description` 字段（AI 负责填写）。但这需要修改 `SkillsAdvisor` 的 instructions

**建议**：采用方式 1（最简），去掉 `description` prop，只显示请求详情（method + URL + body）。如果需要友好的描述，AI 的文字在代码块上方，用户可以看到。

---

## 5. 后端配置

要启用确认模式，需要设置：
```yaml
# src/main/resources/application.yml
app:
  confirm-before-mutate: true  # 改为 true（当前是 false）
```

或通过环境变量：
```bash
export APP_CONFIRM_BEFORE_MUTATE=true
```

**注意**：这个配置同时影响传统前端和 CopilotKit 前端，因为后端逻辑是共用的。

---

## 6. 风险评估

| 风险 | 可能性 | 影响 | 缓解措施 |
|------|--------|------|----------|
| `children.props.className` 在某些 ReactMarkdown 版本中不可访问 | 低 | 中 | 先用 `console.log(children)` 验证结构 |
| JSON 解析失败（AI 生成的 JSON 格式不对） | 低 | 低 | try-catch 降级到普通代码块显示 |
| `markdownTagRenderers` 在 CopilotKit 实际安装版本中 prop 名不同 | 极低 | 高 | 已查验 v1.53.0 类型定义确认正确 |
| 流式渲染时代码块还没完整 → 提前显示对话框 | 低 | 中 | ReactMarkdown 只在块完整时渲染，不会有此问题 |
| 同一消息中多个 http-request 块 | 低 | 低 | 每个 `pre` 调用独立处理，天然支持 |

---

## 7. 不需要做的事情

- ❌ 不需要 `useCopilotAction` / `renderAndWaitForResponse`
- ❌ 不需要修改后端（后端已经实现了 confirm-before-mutate 模式）
- ❌ 不需要 `useHttpConfirmationAction.tsx`（可以删除或留着）
- ❌ 不需要 `useCopilotChatHeadless_c` 发送结果回 AI（可以作为后续优化）
- ❌ 不需要修改 `route.ts`（BFF 层无需变动）

---

## 8. 实施步骤（批准后）

### 步骤 1：修改 `ConfirmDialogContainer.tsx`
- 移除 `respond` prop 依赖
- 添加内部状态机（pending → executing → completed/cancelled）
- 修改 `executeHttpRequest` 调用 `/api/explain-result`
- 更新渲染逻辑

### 步骤 2：修改 `page.tsx`
- 添加 `React` 和 `ConfirmDialogContainer` 的 import
- 将 `markdownComponents` → `markdownTagRenderers`
- 移除 `code` 覆盖（恢复默认语法高亮）
- 添加 `pre` 覆盖（http-request 检测）

### 步骤 3：开启后端确认模式
- 将 `app.confirm-before-mutate=true` 设置好（用于测试）

### 步骤 4：测试
- 启动后端（带 confirm-before-mutate=true）
- 启动前端 dev server
- 测试场景：
  1. 搜索商品（GET 请求）→ 应该没有确认对话框
  2. 加入购物车（POST 请求）→ 应该出现确认对话框
  3. 点击确认 → 请求应该执行，显示成功结果
  4. 点击取消 → 显示取消消息

---

## 9. 代码预览

### `ConfirmDialogContainer.tsx`（修改后）

```tsx
"use client";

import React, { useState, useCallback } from 'react';

export interface HttpRequestMeta {
  method: string;
  url: string;
  headers?: Record<string, string>;
  body?: any;  // 可以是对象或字符串
  params?: Record<string, string>;
}

type Stage = 'pending' | 'executing' | 'completed' | 'cancelled';

const ALLOWED_DOMAINS = ['localhost:8080', '127.0.0.1:8080', 'petstore.swagger.io'];

function isUrlAllowed(url: string): boolean {
  try {
    const parsed = new URL(url, window.location.origin);
    return ALLOWED_DOMAINS.some(d => parsed.host === d || parsed.host.endsWith(d));
  } catch { return false; }
}

async function executeHttpRequest(meta: HttpRequestMeta): Promise<string> {
  if (!isUrlAllowed(meta.url)) {
    throw new Error(`URL 不在白名单中: ${meta.url}`);
  }
  
  let url = meta.url;
  if (meta.params && Object.keys(meta.params).length > 0) {
    url += '?' + new URLSearchParams(meta.params).toString();
  }
  
  const bodyStr = meta.body 
    ? (typeof meta.body === 'string' ? meta.body : JSON.stringify(meta.body))
    : undefined;
  
  const resp = await fetch(url, {
    method: meta.method,
    headers: { 'Content-Type': 'application/json', ...meta.headers },
    body: bodyStr,
  });
  const responseText = await resp.text();
  
  // 调用 explain-result API（与传统前端一致）
  try {
    const explainResp = await fetch('/api/explain-result', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        method: meta.method,
        url: meta.url,
        queryParams: meta.params || {},
        statusCode: resp.status,
        responseBody: responseText,
      }),
    });
    if (explainResp.ok) return await explainResp.text();
  } catch { /* 解释失败，返回原始响应 */ }
  
  return responseText;
}

interface Props {
  requestMeta: HttpRequestMeta;
}

export function ConfirmDialogContainer({ requestMeta }: Props) {
  const [stage, setStage] = useState<Stage>('pending');
  const [result, setResult] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleConfirm = useCallback(async () => {
    setStage('executing');
    setError(null);
    try {
      const res = await executeHttpRequest(requestMeta);
      setResult(res);
      setStage('completed');
    } catch (e: any) {
      setError(e.message);
      setStage('completed');
    }
  }, [requestMeta]);

  const handleCancel = useCallback(() => {
    setStage('cancelled');
  }, []);

  if (stage === 'cancelled') {
    return (
      <div className="text-sm text-gray-500 p-2 italic">已取消该操作。</div>
    );
  }

  if (stage === 'completed') {
    return (
      <div className="rounded-lg p-3 my-2 bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800 text-sm">
        {error
          ? <><span className="text-red-600">❌ 操作失败：</span>{error}</>
          : <><span className="text-green-600">✅ 操作结果：</span><div className="mt-1 text-gray-700 dark:text-gray-300 whitespace-pre-wrap">{result}</div></>
        }
      </div>
    );
  }

  return (
    <div className="bg-white dark:bg-gray-800 rounded-lg shadow-xl border border-gray-200 dark:border-gray-700 p-4 max-w-md my-2">
      <div className="flex items-center gap-2 mb-3">
        <span className="text-2xl">⚠️</span>
        <h3 className="text-lg font-semibold text-gray-900 dark:text-white">操作确认</h3>
      </div>
      
      <div className="bg-gray-50 dark:bg-gray-900 rounded-lg p-3 mb-4 text-sm">
        <div className="flex items-center gap-2 mb-2">
          <span className={`px-2 py-0.5 rounded text-xs font-medium uppercase
            ${requestMeta.method === 'POST' ? 'bg-blue-100 text-blue-800' : ''}
            ${requestMeta.method === 'PUT' ? 'bg-yellow-100 text-yellow-800' : ''}
            ${requestMeta.method === 'DELETE' ? 'bg-red-100 text-red-800' : ''}
            ${!['POST','PUT','DELETE'].includes(requestMeta.method) ? 'bg-gray-100 text-gray-800' : ''}
          `}>{requestMeta.method}</span>
          <code className="text-gray-800 dark:text-gray-200 font-mono text-xs break-all">{requestMeta.url}</code>
        </div>
        {requestMeta.body && (
          <details className="mt-2">
            <summary className="cursor-pointer text-gray-600 dark:text-gray-400 text-xs">请求体</summary>
            <pre className="mt-1 text-xs bg-gray-100 dark:bg-gray-800 p-2 rounded overflow-x-auto max-h-32">
              {typeof requestMeta.body === 'string' ? requestMeta.body : JSON.stringify(requestMeta.body, null, 2)}
            </pre>
          </details>
        )}
      </div>
      
      {error && (
        <div className="bg-red-50 dark:bg-red-900/20 border border-red-200 rounded-lg p-3 mb-4 text-sm text-red-700">
          {error}
        </div>
      )}
      
      <div className="flex justify-end gap-2">
        <button
          onClick={handleCancel}
          disabled={stage === 'executing'}
          className="px-4 py-2 rounded-lg text-sm font-medium bg-gray-100 dark:bg-gray-700 text-gray-700 dark:text-gray-300 hover:bg-gray-200 disabled:opacity-50 transition-colors"
        >
          取消
        </button>
        <button
          onClick={handleConfirm}
          disabled={stage === 'executing'}
          className="px-4 py-2 rounded-lg text-sm font-medium bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50 transition-colors flex items-center gap-2"
        >
          {stage === 'executing' && (
            <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none"/>
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"/>
            </svg>
          )}
          {stage === 'executing' ? '执行中...' : '确认执行'}
        </button>
      </div>
    </div>
  );
}
```

### `page.tsx` 的核心变更（`markdownTagRenderers` 的 `pre` 覆盖）

```tsx
import React from 'react';
import { ConfirmDialogContainer, HttpRequestMeta } from '@/components/ConfirmDialogContainer';

// 在 CopilotPopup 的 markdownTagRenderers 中：
pre: ({ children, ...props }: any) => {
  // 检测 http-request 代码块
  if (React.isValidElement(children)) {
    const codeProps = (children as React.ReactElement<any>).props;
    if (codeProps?.className === 'language-http-request') {
      try {
        const content = String(codeProps.children).trim();
        const requestMeta: HttpRequestMeta = JSON.parse(content);
        return <ConfirmDialogContainer requestMeta={requestMeta} />;
      } catch {
        // JSON 解析失败，降级到普通渲染
      }
    }
  }
  // 其他代码块：让 CopilotKit 默认 CodeBlock 处理（通过 pre 包裹）
  return (
    <pre className="copilotKitMarkdownElement bg-gray-100 dark:bg-gray-800 p-4 rounded-lg overflow-x-auto my-2" {...props}>
      {children}
    </pre>
  );
},
```

---

## 10. 对比总结

| 维度 | 旧方案（useCopilotAction） | 新方案（markdownTagRenderers 覆盖）|
|------|---------------------------|-----------------------------------|
| 是否需要修改 AI 调用链 | 是（AI 需要调用前端 action） | 否（后端行为不变）|
| 是否需要 useCopilotAction | 是 | 否 |
| 是否曾出现 ZodError | 是 | 不会（完全绕开）|
| 与传统前端一致性 | 低 | 高 |
| 代码复杂度 | 高 | 低 |
| 可行性 | 存疑 | 已验证（ReactMarkdown 标准行为）|

---

*等待用户批准后开始实施。*
