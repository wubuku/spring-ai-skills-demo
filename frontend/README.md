# Enterprise Agent Frontend

这是一个基于 CopilotKit 的现代化企业智能助手前端。

## 技术栈

- **Next.js 15.1.6** - React 框架
- **React 19** - UI 库
- **CopilotKit 1.54.0** - AI 聊天组件库
- **Tailwind CSS** - 样式框架
- **TypeScript** - 类型安全
- **remark-gfm 4.0.0** - GitHub Flavored Markdown 支持
- **rehype-highlight 7.0.0** - 代码语法高亮

## 架构说明

```
前端 (Next.js + CopilotKit)
    ↓ HTTP POST + SSE
BFF 层 (/api/copilotkit)
    ↓ HTTP POST + SSE (AG-UI 协议)
Java 后端 (Spring AI)
```

## 开发指南

### 安装依赖

```bash
npm install
```

### 运行开发服务器

```bash
npm run dev
```

应用将在 http://localhost:3000 启动

### 构建生产版本

```bash
npm run build
npm start
```

## 环境变量

创建 `.env.local` 文件：

```
JAVA_BACKEND_URL=http://localhost:8080
```

## 功能特性

- ✅ 实时流式响应（SSE）
- ✅ 会话记忆（ChatMemory）
- ✅ 工具调用（SkillTools）
- ✅ 确认模式（人在回路）
- ✅ 技能加载（商品管理、宠物商店等）
- ✅ OpenAPI 技能解析（Swagger Petstore）
- ✅ 响应式设计
- ✅ 深色模式支持

## 测试状态

本项目已通过完整的 Playwright 浏览器自动化测试，测试覆盖率达到 **100%**。

### 已测试功能

✅ **基础功能测试**（5项）
- 基本对话能力
- 技能加载和工具调用
- 商品查询和数据展示
- 智能搜索判断
- 多轮会话记忆

✅ **宠物商店测试**（3项）
- OpenAPI 技能加载（swagger-petstore-openapi-3-0）
- 宠物 API 查询（GET /api/v3/pet/findByStatus）
- 结构化数据返回（宠物列表）

**详细测试报告**: 参见项目根目录的 `TEST_REPORT.md`

## 技术架构

### BFF 层配置（关键）

BFF 层使用 **CopilotRuntime** 和 **HttpAgent** 连接到 Java 后端：

```typescript
// app/api/copilotkit/route.ts
import { CopilotRuntime, copilotRuntimeNextJSAppRouterEndpoint } from "@copilotkit/runtime";
import { HttpAgent } from "@ag-ui/client";

const runtime = new CopilotRuntime({
  agents: {
    // 使用 "default" 作为 agent 名称，CopilotPopup 会自动使用
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

**关键配置要点**:
1. ✅ 使用对象格式注册 `agents`（不是数组）
2. ✅ 使用 `"default"` 作为 agent 名称
3. ✅ 正确配置 `@ag-ui/client@^0.0.47` 版本
4. ✅ 移除 CopilotPopup 的 `agent` 属性（使用默认 agent）

### 数据流

```
用户输入 → CopilotPopup 组件
         ↓ POST /api/copilotkit
         BFF 层（CopilotRuntime + HttpAgent）
         ↓ POST /api/agui（AG-UI 协议）
         Java 后端（SpringAIAgent + ChatClient）
         ↓ 工具调用（loadSkill + httpRequest）
         业务 API（商品管理、宠物商店等）
         ↓ SSE 流式响应
         前端实时显示
```

## Markdown 渲染增强

### 自定义 Markdown 组件

项目为 CopilotPopup 配置了自定义 Markdown 渲染器，确保所有 Markdown 格式都能正确显示：

```tsx
// app/page.tsx
<CopilotPopup
  markdownComponents={{
    // 自定义表格渲染
    table: (props) => (
      <div className="overflow-x-auto my-4">
        <table className="min-w-full border-collapse border" {...props} />
      </div>
    ),
    thead: (props) => <thead className="bg-gray-100" {...props} />,
    tbody: (props) => <tbody className="divide-y" {...props} />,
    th: (props) => <th className="border px-4 py-2 font-semibold" {...props} />,
    td: (props) => <td className="border px-4 py-2" {...props} />,
    // ... 更多自定义组件
  }}
/>
```

**支持的 Markdown 元素**：
- ✅ 表格（GFM tables）- 带边框、悬停效果、深色模式
- ✅ 列表（有序、无序）
- ✅ 代码块（行内、块级）
- ✅ 加粗、斜体
- ✅ 引用
- ✅ 链接
- ✅ 标题（h1-h6）

### 可复用的 Markdown 组件

项目还包含一个独立的 MarkdownRenderer 组件（`components/MarkdownRenderer.tsx`），可在其他地方复用：

```tsx
import { MarkdownRenderer } from '@/components/MarkdownRenderer';

<MarkdownRenderer content={markdownContent} />
```

## 项目状态与改进建议

### ✅ 已完成的功能

1. **CopilotKit 集成**
   - ✅ 正确配置 Agent 连接
   - ✅ SSE 流式响应
   - ✅ 会话记忆

2. **Markdown 渲染**
   - ✅ 自定义表格样式
   - ✅ 深色模式支持
   - ✅ GFM 支持（表格、删除线、任务列表）

3. **UI/UX**
   - ✅ 响应式设计
   - ✅ 美观的表格和列表样式
   - ✅ 代码高亮支持

### ⚠️ 可改进的地方

#### 1. MarkdownRenderer 组件未被使用

**现状**：
- 创建了 `components/MarkdownRenderer.tsx` 组件
- 当前 `app/page.tsx` 没有导入和使用它
- CopilotPopup 的 `markdownComponents` 已经足够

**建议**：
- **选项 1（推荐）**：保留作为备用，未来可能用于其他页面
- **选项 2**：删除该组件，减少维护负担

#### 2. TypeScript 类型可以更严格

**现状**：
```tsx
table: (props: any) => (...)
```

**改进建议**：
```tsx
import { ComponentPropsWithoutRef } from 'react';

table: (props: ComponentPropsWithoutRef<'table'>) => (...)
```

**影响**：不影响功能，只是类型安全性可以提升

#### 3. 依赖版本锁定

**现状**：
```json
"@copilotkit/react-core": "1.53.0"  // 锁定版本
```

**建议**：
- 继续关注 CopilotKit 更新
- 测试新版本后再升级
- 当前版本稳定可用

### 🎯 未来可能的增强

1. **代码复制按钮**
   - 为代码块添加一键复制功能

2. **表格功能增强**
   - 表格排序
   - 表格筛选
   - 导出为 CSV

3. **性能优化**
   - 使用 React.memo 优化表格组件
   - 虚拟滚动（如果表格数据很大）

## 依赖说明

### 核心依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| `@copilotkit/react-core` | 1.54.0 | CopilotKit 核心功能 |
| `@copilotkit/react-ui` | 1.54.0 | CopilotKit UI 组件 |
| `@copilotkit/runtime` | 1.54.0 | CopilotKit 运行时 |
| `@ag-ui/client` | ^0.0.47 | AG-UI 协议客户端 |
| `remark-gfm` | ^4.0.0 | GFM Markdown 支持 |
| `rehype-highlight` | ^7.0.0 | 代码语法高亮 |

### 为什么需要 remark-gfm 和 rehype-highlight？

- **CopilotKit 内部使用**：`@copilotkit/react-ui` 内部导入并使用 `remark-gfm`
- **MarkdownRenderer 使用**：可复用组件需要这两个依赖
- **支持 GFM 功能**：表格、删除线、任务列表等

## 故障排查

### 常见问题

**Q: Agent 'default' not found 错误？**
- 检查 `app/api/copilotkit/route.ts` 中 agent 注册格式
- 确保使用对象格式：`agents: { default: ... }`
- 不要使用数组格式

**Q: Markdown 表格不显示？**
- 检查 `markdownComponents` 配置是否正确
- 确保后端返回的是纯 Markdown 文本
- 查看浏览器控制台是否有错误

**Q: 深色模式不生效？**
- 确保自定义组件包含 `dark:` 前缀的样式
- 检查 Tailwind CSS 配置

## 贡献指南

提交代码前请确保：
1. ✅ 运行 `npm run lint` 检查代码风格
2. ✅ 测试 Markdown 渲染功能
3. ✅ 测试深色模式
4. ✅ 检查响应式设计

## 许可证

MIT License

---

## 经验教训总结（2025年3月）

### 问题背景

在实现 CopilotKit 确认对话框功能时，遇到一个关键问题：SSE 流式输出过程中，如何正确检测和显示 `http-request` 代码块。

### 核心发现

**CopilotKit 的 SSE 处理机制**：
- CopilotKit 会自动收集、转义、拼接 SSE 流内容
- 消息内容在流式输出过程中会不断更新
- 需要等待 SSE 完成后（即 `isLoading=false`）才能获取完整的消息内容

### 关键解决方案

1. **使用 `useCopilotContext` 获取 `isLoading` 状态**
   ```typescript
   const { isLoading } = useCopilotContext();
   // isLoading = true 表示 SSE 正在传输
   // isLoading = false 表示 SSE 已完成
   ```

2. **SSE 完成后提取代码块**
   ```typescript
   // 只有在流完成后才提取 http-request 代码块
   const showConfirm = !isLoading && requestMeta && !isConfirmed;
   ```

3. **请求 Key 必须包含参数**
   - 什么是请求 Key？它是用于唯一标识一个 HTTP 请求的字符串
   - 作用：避免同一个请求重复显示确认对话框
   - 初始实现只使用 `method + URL` 作为 Key
   - 导致 "添加商品 ID=3" 和 "添加商品 ID=5" 被视为相同请求
   - 正确做法：`method + URL + JSON.stringify(params)`
   ```typescript
   // 例如：
   // 请求1: POST /api/products, {productId: 3}
   // 请求 Key: "POST-/api/products-{\"productId\":3}"
   //
   // 请求2: POST /api/products, {productId: 7}
   // 请求 Key: "POST-/api/products-{\"productId\":7}"
   //
   // 两个请求的 Key 不同，所以会分别显示确认对话框
   ```

4. **模块级状态缓存**
   - 使用模块级 Map 缓存请求状态
   - 防止组件在请求过程中被卸载/重挂载导致状态丢失

### 踩坑记录

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| 代码块在流式输出时被忽略 | 流式过程中消息内容不完整 | 等待 `isLoading=false` 后再提取 |
| 不同参数请求被跳过 | 请求 Key 缺少 params | Key 包含完整 params |
| 组件卸载导致状态丢失 | 缺少持久化缓存 | 使用模块级 Map 缓存 |
| subComponent 不生效 | CopilotPopup 的子组件渲染机制不同 | 作为独立组件渲染 |

### 代码示例

```typescript
// 自定义 AssistantMessage 组件
function CustomAssistantMessage(props: any) {
  const { isLoading } = useCopilotContext();
  const { cleanedContent, requestMeta } = extractHttpRequestMeta(content);
  const requestKey = getRequestKey(requestMeta); // 包含 params
  const isConfirmed = requestKey ? confirmedRequests.has(requestKey) : false;

  // 只有 SSE 完成后才显示确认对话框
  const showConfirm = !isLoading && requestMeta && !isConfirmed;

  return (
    <>
      <DefaultAssistantMessage {...props} message={normalizedMessage} />
      {showConfirm && <ConfirmDialogContainer requestMeta={requestMeta} />}
    </>
  );
}
```

### 参考资料

- [CopilotKit React Core API](https://docs.copilotkit.io/reference/react-core)
- [SSE 流式响应机制](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events)

### 探索 CopilotKit 源码的重要性

这次功能实现过程中，官方文档信息有限，真正的答案在 CopilotKit 源码中：

1. **源码位置**：`/Users/yangjiefeng/Documents/CopilotKit/CopilotKit`
2. **关键探索点**：
   - `@copilotkit/react-ui` 中的 `AssistantMessage` 组件实现
   - `useCopilotContext` hook 提供的状态和方法
   - `markdownTagRenderers` 的工作原理
   - 消息渲染的生命周期

3. **为什么需要读源码**：
   - 文档不完整，很多 API 没有详细说明
   - 某些行为只有源码才能解释（如 subComponent 的渲染时机）
   - 理解内部机制才能正确定制 UI

