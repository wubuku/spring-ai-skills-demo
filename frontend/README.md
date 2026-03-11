# Enterprise Agent Frontend

这是一个基于 CopilotKit 的现代化企业智能助手前端。

## 技术栈

- **Next.js 15** - React 框架
- **CopilotKit** - AI 聊天组件库
- **Tailwind CSS** - 样式框架
- **TypeScript** - 类型安全

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
