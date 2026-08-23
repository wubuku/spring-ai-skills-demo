# CopilotKit 集成指南

> **文档状态**: 通用集成参考，部分示例来自历史实现。
> **最后核对**: 2026-08-23
>
> 当前项目使用 Next.js `4000` 端口、CopilotKit `1.60.x`、CopilotKit v2
> `CopilotKitProvider`/`useSingleEndpoint` 和浏览器 `useHumanInTheLoop`。
> 本文的示例用于解释集成模式，不能替代项目的
> [前端指南](../frontend/README.md) 和 [架构说明](ARCHITECTURE.md)。

> **适用场景**: React + Node.js 一体化项目（前端 + BFF 在同一项目）
> **集成时间**: 10-15 分钟 | **代码量**: ~35 行
> **前置要求**: Node.js 项目 + Java Spring AI 后端

---

## 目录

1. [背景知识：四个包的关系与区别](#背景知识四个包的关系与区别)
2. [典型架构](#典型架构)
3. [快速集成](#快速集成)
4. [关键配置](#关键配置)
5. [常见问题](#常见问题)
6. [最佳实践](#最佳实践)

---

## 背景知识：四个包的关系与区别

### 概述

这四个包属于两个**相关但独立**的项目：

| 包 | 所属项目 | 职责 |
|----|----------|------|
| `@ag-ui/client` | AG-UI Protocol | AG-UI 协议的 TypeScript 客户端 SDK |
| `@copilotkit/react-core` | CopilotKit | React 核心包（Hooks 和上下文） |
| `@copilotkit/react-ui` | CopilotKit | React UI 组件库 |
| `@copilotkit/runtime` | CopilotKit | 后端运行时（连接前端与 AI 模型） |

### AG-UI Protocol（重要：独立于 CopilotKit）

**AG-UI 是一个独立的开放协议**，用于解决 AI Agent 与前端应用之间的通信交互问题。它不是由 CopilotKit 团队创建的，而是作为一个开放标准被多个项目采用。

**官网**: https://ag-ui.com
**GitHub**: https://github.com/ag-ui-protocol/ag-ui
**许可证**: Apache-2.0（JS/TS SDK）

#### 核心功能

- 提供 `HttpAgent` 等实现，用于连接到 AG-UI 协议服务器
- 支持 SSE（Server-Sent Events）和 protobuf 流式传输
- 自动处理 AG-UI 事件的完整生命周期：连接、事件处理、状态变更和错误管理
- 内置响应式状态管理，自动跟踪消息和 Agent 状态并提供实时更新
- 支持中间件系统（日志记录、持久化、自定义逻辑处理）
- 提供 `AbstractAgent` 基类，允许开发者构建自己的传输层实现

#### Java 实现

本项目使用 `ag-ui-4j`（MIT License）：
- **GitHub**: https://github.com/Work-m8/ag-ui-4j (本项目子模块)
- **协议规范**: https://github.com/ag-ui-protocol/ag-ui
- **许可证**: MIT（与 JS SDK 的 Apache-2.0 不同）

> ⚠️ **注意**：AI 助手回复中关于 AG-UI 许可证的说法不准确。AG-UI Protocol 的 JS/TS SDK 使用 Apache-2.0，而 Java 实现（ag-ui-4j）使用 MIT 许可证。

### CopilotKit

**官网**: https://copilotkit.ai
**GitHub**: https://github.com/CopilotKit/CopilotKit
**许可证**: MIT

CopilotKit 是一个全栈框架，使用 AG-UI 协议作为其底层通信机制。

#### 核心组件

**@copilotkit/react-core** - React 核心包
- 提供 `CopilotKit` 上下文提供者，用于全局配置和状态管理
- 导出核心 hooks：
  - `useCopilotChat()`：管理聊天会话、消息历史和流式输出
  - `useCopilotAction()`：定义 AI 可以调用的前端函数
  - `useAgent()`：连接到 AG-UI Agent，获取其实时状态、消息和工具调用信息
- 处理与 CopilotKit 运行时的通信
- 支持多 Agent 管理和会话持久化

**@copilotkit/react-ui** - React UI 组件包
- 提供完整的聊天界面组件：
  - `CopilotSidebar`：侧边栏式 AI 助手
  - `CopilotPopup`：弹出式 AI 助手
  - `CopilotChat`：独立的聊天窗口组件
- 内置消息气泡、输入框、加载状态、工具调用展示等 UI 元素
- 支持深度自定义：可以通过 CSS 覆盖样式，也可以替换子组件

**@copilotkit/runtime** - 后端运行时包
- 接收来自前端的请求，调用 LLM 并将结果流式返回给客户端
- 提供与主流 AI 模型提供商的适配器：OpenAI、Anthropic、Google Gemini 等
- 内置与主流 Agent 框架的集成：LangGraph、CrewAI、Mastra、AG-UI 等
- 提供身份验证、请求验证和 API 密钥安全管理
- 处理 AG-UI 协议的事件流转换和传输

### 它们之间的关系（关键纠正）

```
┌─────────────────────────────────────────────────────────────┐
│  前端应用                                                     │
│  ├── @copilotkit/react-ui (UI组件)                           │
│  └── @copilotkit/react-core (React hooks和状态管理)            │
│                           ↓                                   │
│  @ag-ui/client (AG-UI协议客户端)                              │
│                           ↓                                   │
│  网络通信 (HTTP/SSE)                                           │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│  @copilotkit/runtime (后端运行时)                             │
│  ├── 连接到 CopilotKit 前端                                    │
│  └── 连接到 AI 模型 / Agent 框架                               │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│  AG-UI 协议 (独立项目，非 CopilotKit 专有)                      │
│  ├── Java 实现: ag-ui-4j (本项目使用)                          │
│  ├── TS/JS 实现: @ag-ui/client                               │
│  └── Python/Dart/Rust 等多语言 SDK                            │
└───────────────────────────────────────────────────────────────┘
```

**关键纠正**：

1. ❌ AI 助手说 "AG-UI 是 CopilotKit 的底层协议" - **部分正确但不准确**
   - AG-UI 是一个独立开放的协议，不是 CopilotKit 创建的
   - CopilotKit 采用了 AG-UI 作为其通信协议

2. ❌ AI 助手说 "CopilotKit 是 AG-UI 的实现和扩展" - **不准确**
   - CopilotKit 是独立的项目，它使用 AG-UI 协议
   - AG-UI 有自己的多语言实现（Java、Python、TypeScript 等）

3. ✅ 正确的描述：
   - AG-UI 是一个独立的协议规范（类似 WebSocket 或 HTTP）
   - CopilotKit 是基于 AG-UI 协议的全栈框架
   - 本项目使用 ag-ui-4j（Java 实现）来支持 AG-UI 协议

### 版本信息（AI 助手提供的版本已过时）

**当前项目实际使用的版本**（2026-05）：

```json
{
  "@ag-ui/client": "^0.0.47",
  "@copilotkit/react-core": "^1.54.0",
  "@copilotkit/react-ui": "^1.54.0",
  "@copilotkit/runtime": "^1.54.0"
}
```

> ⚠️ AI 助手回复中提到的版本（^0.0.35, ^1.10.1）已经过时，请使用上述版本。

---

## 典型架构

### 一种典型的企业应用架构

```
┌─────────────────────────────────────────────────────────────┐
│  Node.js 项目（前端 + BFF 一体化）                             │
│                                                              │
│  ├── 前端代码                                                 │
│  │   └── src/                                                │
│  │                                                           │
│  ├── BFF 层                                                   │
│  │   ├── src/api/           ← REST API 路由                  │
│  │   └── server.js          ← Express/Next.js 服务器         │
│  │                                                           │
│  └── CopilotKit 集成 🆕                                       │
│      ├── src/api/copilotkit/route.ts  ← 添加一个文件          │
│      └── src/App.tsx                  ← 添加 Provider        │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│  Java 后端（Spring AI + ag-ui-4j）                           │
│  ├── /api/products   - 业务 API                              │
│  └── /api/agui       - AG-UI 协议端点 🆕                      │
└─────────────────────────────────────────────────────────────┘
```

**为什么这种架构容易集成？**
- BFF 层已存在，只需添加一个路由
- 共享认证、配置、中间件
- 不需要额外部署服务

---

## 快速集成

### 步骤 1: 安装依赖

```bash
npm install @copilotkit/react-core @copilotkit/react-ui @copilotkit/runtime @ag-ui/client@^0.0.47
```

### 步骤 2: 添加 BFF 路由

根据你的框架选择：

**Next.js App Router** (推荐):
```typescript
// app/api/copilotkit/route.ts
import { CopilotRuntime, copilotRuntimeNextJSAppRouterEndpoint } from "@copilotkit/runtime";
import { HttpAgent } from "@ag-ui/client";

const runtime = new CopilotRuntime({
  agents: {
    default: new HttpAgent({  // ⚠️ 必须用 "default" 名称
      url: `${process.env.JAVA_BACKEND_URL || "http://localhost:8080"}/api/agui`,
    }),
  },
});

export const POST = copilotRuntimeNextJSAppRouterEndpoint({
  runtime,
  endpoint: "/api/copilotkit",
});
```

**Express**:
```typescript
// server.js
import { CopilotRuntime } from "@copilotkit/runtime";
import { HttpAgent } from "@ag-ui/client";

const runtime = new CopilotRuntime({
  agents: {
    default: new HttpAgent({
      url: `${process.env.JAVA_BACKEND_URL || "http://localhost:8080"}/api/agui`,
    }),
  },
});

app.post("/api/copilotkit", async (req, res) => {
  await runtime.handleRequest(req, res);
});
```

### 步骤 3: 添加 Provider

```tsx
// src/App.tsx 或 src/main.tsx
import { CopilotKit } from "@copilotkit/react-core";

function App() {
  return (
    <CopilotKit runtimeUrl="/api/copilotkit">
      <YourExistingApp />
    </CopilotKit>
  );
}
```

### 步骤 4: 添加聊天按钮

```tsx
// 任意页面或布局组件
import { CopilotPopup } from "@copilotkit/react-ui";
import "@copilotkit/react-ui/styles.css";  // ⚠️ 记得导入

function Layout() {
  return (
    <>
      <YourExistingLayout />
      <CopilotPopup
        instructions="你是企业智能助手。"
        labels={{
          title: "智能助手",
          initial: "你好！有什么可以帮助你的吗？",
          placeholder: "输入问题...",
        }}
      />
    </>
  );
}
```

**完成！** 🎉

访问应用，右下角会出现聊天按钮。

---

## 关键配置

### Agent 注册（最容易出错）

```typescript
// ❌ 错误
agents: [new HttpAgent(...)]           // 不能用数组
agents: { "my-agent": new HttpAgent }  // 名称不是 default
<CopilotPopup agent="my-agent" />      // 不能指定 agent

// ✅ 正确
agents: { default: new HttpAgent(...) }
<CopilotPopup />
```

### Java 后端 CORS

```java
@Configuration
public class CorsConfig {
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:4000"));
        config.setAllowedMethods(List.of("*"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }
}
```

---

## 常见问题

### Q: Agent 'default' not found 错误？

**原因**: Agent 注册配置错误

**解决**: 确保使用对象格式 + "default" 名称（见上方"关键配置"）

### Q: CORS 错误？

**解决**: 在 Java 后端添加 CorsFilter（见上方配置）

### Q: 聊天按钮不显示？

**检查**:
- 是否导入样式: `import "@copilotkit/react-ui/styles.css";`
- 是否添加了 CopilotKit Provider
- 是否添加了 CopilotPopup 组件

### Q: 需要认证？

```typescript
// BFF 路由中添加认证
export async function POST(req: NextRequest) {
  const token = req.headers.get("authorization");
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }
  return handleRequest(req);
}
```

### Q: 支持会话记忆吗？

支持，在 Java 后端配置：
```java
@Bean
public ChatMemory chatMemory(JdbcChatMemoryRepository repo) {
    return MessageWindowChatMemory.builder()
        .chatMemoryRepository(repo)
        .maxMessages(20)
        .build();
}
```

---

## 最佳实践

### 1. 渐进式集成

推荐流程：
- **第 1 周**: 添加基础聊天功能
- **第 2 周**: 定制业务技能
- **第 3 周**: 添加 AI 辅助表单
- **第 4 周**: 添加操作确认机制

### 2. 技能设计

按业务模块创建技能：
```
skills/
├── product-management/SKILL.md
├── order-management/SKILL.md
└── user-management/SKILL.md
```

### 3. 人在回路

敏感操作添加确认：
```java
@Tool(description = "删除商品")
public String deleteProduct(Long id) {
    if (confirmBeforeMutate) {
        return "[CONFIRM_REQUIRED]\n确认删除？\n```http-request\n{...}\n```";
    }
    productService.delete(id);
    return "已删除";
}
```

### 4. 渐进式加载技能

```java
@Tool(description = "加载技能模块")
public String loadSkill(String name) {
    return registry.get(name).getBody();  // 按需加载
}
```

**好处**: 减少 60-90% token 消耗

---

## 参考资源

- **CopilotKit GitHub**: https://github.com/CopilotKit/CopilotKit
- **AG-UI Protocol GitHub**: https://github.com/ag-ui-protocol/ag-ui
- **AG-UI-4J (Java 实现)**: https://github.com/Work-m8/ag-ui-4j
- **AG-UI 官网**: https://ag-ui.com
- **完整指南**: `docs/drafts/enterprise-agent-frontend-guide-v4.md`
- **AG-UI 协议**: `docs/drafts/spring-ai-agui-guide.md`
- **测试报告**: `TEST_REPORT.md`
- **示例代码**: `frontend/` 目录
- **测试验证**: 18/18 通过（100%）

---

## 总结

**4 步集成**:
1. 安装依赖
2. 添加 BFF 路由（15-17 行）
3. 添加 Provider（3 行）
4. 添加聊天按钮（5 行）

**无需**:
- ❌ 创建新服务
- ❌ 修改业务代码
- ❌ 重构架构

**现在开始！** 🚀
