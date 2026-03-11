# 企业智能助手 - CopilotKit 集成完成

## 项目概述

成功将 CopilotKit 集成到现有的 Spring AI 项目中，实现了一个现代化的智能体前端界面，同时保留了所有现有的后端业务逻辑。

## 架构说明

```
┌─────────────────────────────────────────────────────────────┐
│  前端层：Next.js 15 + React + CopilotKit                       │
│  http://localhost:3001                                         │
│                                                                │
│  - CopilotPopup: 开箱即用的聊天界面                            │
│  - Tailwind CSS: 现代化样式                                    │
│  - TypeScript: 类型安全                                        │
└────────────────────┬────────────────────────────────────────┘
                     │ HTTP POST + SSE (AG-UI 协议)
                     ▼
┌─────────────────────────────────────────────────────────────┐
│  BFF 层：CopilotKit Runtime (Node.js)                         │
│  /api/copilotkit/route.ts                                     │
│                                                                │
│  - 代理请求到 Java 后端                                        │
│  - 协议转换 (CopilotKit <-> AG-UI)                            │
│  - 零业务逻辑，纯透传层                                        │
└────────────────────┬────────────────────────────────────────┘
                     │ HTTP POST + SSE (AG-UI 协议)
                     ▼
┌─────────────────────────────────────────────────────────────┐
│  业务层：Spring AI Java 后端                                   │
│  http://localhost:8080                                         │
│                                                                │
│  现有功能（已保留）：                                           │
│  - /api/chat: 原有聊天接口                                     │
│  - /api/products: 商品相关接口                                 │
│  - PetStore Mock 后端                                          │
│                                                                │
│  新增功能：                                                     │
│  - /api/agui: AG-UI 协议端点 (SSE 流式响应)                   │
│  - EnterpriseAgent: 包装现有的 ChatClient 和工具               │
│  - 复用 SkillTools、SkillsAdvisor、ChatMemory                 │
└─────────────────────────────────────────────────────────────┘
```

## 已完成的工作

### 1. 后端改造（Java）

#### 拷贝 AG-UI SDK 代码
- 从 `ag-ui-4j` 子模块拷贝了 62 个 Java 文件
- 包含核心类：`SpringAIAgent`, `AgUiService`, `AgentStreamer`
- 保持原有包名 `com.agui.*`

#### 新增配置类
**文件**: `src/main/java/com/example/demo/config/AgUiConfig.java`
- 配置 `AgUiService` Bean
- 配置 `EnterpriseAgent` Bean
- 复用现有的 `ChatModel`, `SkillTools`, `SkillsAdvisor`

#### 新增控制器
**文件**: `src/main/java/com/example/demo/controller/AgUiController.java`
- `POST /api/agui`: AG-UI 协议端点（SSE 流式响应）
- `GET /api/agui/health`: 健康检查
- `GET /api/agui/info`: Agent 信息

#### CORS 配置
**文件**: `src/main/java/com/example/demo/config/CorsConfig.java`
- 允许前端（localhost:3000/3001）跨域访问

### 2. 前端创建（Next.js）

#### 项目结构
```
frontend/
├── app/
│   ├── api/copilotkit/
│   │   └── route.ts          # BFF 层（CopilotKit Runtime）
│   ├── globals.css           # 全局样式（Tailwind）
│   ├── layout.tsx            # 根布局（CopilotKit Provider）
│   └── page.tsx              # 主页面（CopilotPopup）
├── .env.local                # 环境变量
├── next.config.js            # Next.js 配置
├── tailwind.config.js        # Tailwind 配置
├── tsconfig.json             # TypeScript 配置
└── package.json              # 依赖配置
```

#### 核心文件说明

**app/layout.tsx**
- 引入 `CopilotKit` Provider
- 配置 `runtimeUrl="/api/copilotkit"`

**app/page.tsx**
- 使用 `CopilotPopup` 组件（开箱即用）
- 现代化的特性卡片布局
- 使用指南说明

**app/api/copilotkit/route.ts**
- CopilotKit Runtime BFF 层
- 将请求代理到 Java 后端的 `/api/agui`
- 协议转换：CopilotKit GraphQL <-> AG-UI SSE

## 如何使用

### 1. 启动后端

```bash
# 方式 1：使用 Maven
mvn spring-boot:run

# 方式 2：直接运行 JAR
mvn package
java -jar target/spring-ai-skills-demo-1.0.0.jar
```

后端将启动在：http://localhost:8080

### 2. 启动前端

```bash
cd frontend
npm install          # 首次运行需要安装依赖
npm run dev          # 启动开发服务器
```

前端将启动在：http://localhost:3001（如果 3000 被占用）

### 3. 访问应用

打开浏览器访问：http://localhost:3001

- 点击右下角的聊天图标打开对话框
- 输入问题，例如："搜索商品"、"查询订单"、"帮助我更新宠物信息"
- 助手会根据需要加载相应的技能模块
- 对于敏感操作（如删除、修改），助手会请求确认

### 4. 测试 AG-UI 端点

```bash
# 健康检查
curl http://localhost:8080/api/agui/health

# Agent 信息
curl http://localhost:8080/api/agui/info

# 测试对话（SSE 流）
curl -X POST http://localhost:8080/api/agui \
  -H "Content-Type: application/json" \
  -d '{
    "threadId": "test-thread",
    "runId": "test-run",
    "messages": [{"role": "user", "content": "你好"}]
  }'
```

## 技术栈

### 后端
- **Spring Boot 3.4.2**
- **Spring AI 1.1.0**
- **AG-UI Protocol** (社区 SDK)
- **H2 Database** (会话记忆存储)

### 前端
- **Next.js 15.1.6**
- **React 19**
- **CopilotKit 1.3.14**
- **Tailwind CSS 3.4.1**
- **TypeScript 5**

## 关键特性

### ✅ 已实现

1. **实时流式响应**
   - SSE (Server-Sent Events) 实时推送
   - 打字机效果的流式输出

2. **会话记忆**
   - JDBC 持久化存储
   - 保留最近 20 条消息

3. **工具调用**
   - 渐进式技能披露
   - 自动加载技能模块
   - HTTP 请求工具

4. **确认模式**
   - 敏感操作需要用户确认
   - 人在回路（Human-in-the-loop）

5. **跨域支持**
   - 完整的 CORS 配置
   - 支持开发和生产环境

6. **现代化 UI**
   - 响应式设计
   - 深色模式支持
   - 优雅的动画效果

## 环境变量

### 前端 (`.env.local`)
```env
JAVA_BACKEND_URL=http://localhost:8080
```

### 后端 (`application.yml`)
```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: ${OPENAI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: ${OPENAI_MODEL:gpt-4o}

app:
  api:
    base-url: http://localhost:8080
  confirm-before-mutate: false
```

## 后续改进建议

### 1. 前端增强
- [ ] 实现自定义聊天界面（替代 CopilotPopup）
- [ ] 添加工具调用可视化
- [ ] 实现会话历史管理
- [ ] 添加多语言支持

### 2. 后端增强
- [ ] 添加 JWT 认证
- [ ] 实现多租户支持
- [ ] 添加审计日志
- [ ] 优化 SSE 连接管理

### 3. 性能优化
- [ ] 添加 Redis 缓存
- [ ] 实现连接池管理
- [ ] 添加请求限流
- [ ] 优化数据库查询

### 4. 部署优化
- [ ] Docker 容器化
- [ ] Kubernetes 部署配置
- [ ] CI/CD 流程
- [ ] 监控和日志聚合

## 文件清单

### Java 后端新增文件
```
src/main/java/com/example/demo/config/
├── AgUiConfig.java          # AG-UI 配置
└── CorsConfig.java          # CORS 配置

src/main/java/com/example/demo/controller/
└── AgUiController.java      # AG-UI 端点

src/main/java/com/agui/      # AG-UI SDK（62 个文件）
├── core/                    # 核心接口和模型
├── server/                  # 服务端组件
├── spring/                  # Spring 集成
└── json/                    # JSON 工具
```

### 前端新增文件
```
frontend/
├── app/
│   ├── api/copilotkit/route.ts
│   ├── globals.css
│   ├── layout.tsx
│   └── page.tsx
├── .env.local
├── .gitignore
├── next.config.js
├── postcss.config.js
├── tailwind.config.js
├── tsconfig.json
├── package.json
└── README.md
```

## 常见问题

### Q: 前端无法连接到后端？
A: 检查 `.env.local` 中的 `JAVA_BACKEND_URL` 是否正确，确保后端已启动。

### Q: 工具调用没有响应？
A: 检查后端日志，确认 `SkillTools` 是否被正确注入到 `EnterpriseAgent`。

### Q: CORS 错误？
A: 检查 `CorsConfig.java` 是否包含了前端地址，重启后端服务器。

### Q: 会话记忆不工作？
A: 检查 H2 数据库文件 `./data/chat-memory.mv.db` 是否存在，确认有写入权限。

## 总结

本次集成成功实现了：

✅ **零破坏性改造**：保留了所有现有的后端 API 和前端页面
✅ **现代化前端**：基于 CopilotKit 的专业级聊天界面
✅ **AG-UI 协议**：标准的智能体通信协议，支持 SSE 流式响应
✅ **完整功能**：工具调用、会话记忆、确认模式、渐进式技能披露
✅ **易于扩展**：清晰的架构分层，便于后续功能增强

现在您可以：
1. 使用新的现代化前端（http://localhost:3001）
2. 继续使用原有的简单前端（http://localhost:8080）
3. 通过 API 直接调用（http://localhost:8080/api/chat 或 /api/agui）

---

**日期**: 2026-03-11
**版本**: 1.0.0
**作者**: Claude Code
