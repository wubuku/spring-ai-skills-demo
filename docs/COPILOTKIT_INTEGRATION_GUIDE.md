# CopilotKit 集成指南

> **适用场景**: React + Node.js 一体化项目（前端 + BFF 在同一项目）
> **集成时间**: 10-15 分钟 | **代码量**: ~35 行
> **前置要求**: Node.js 项目 + Java Spring AI 后端

---

## 📋 目录

1. [典型架构](#典型架构)
2. [快速集成](#快速集成)
3. [关键配置](#关键配置)
4. [常见问题](#常见问题)
5. [最佳实践](#最佳实践)

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
│  Java 后端（Spring AI）                                       │
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
        config.setAllowedOrigins(List.of("http://localhost:3000"));
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
