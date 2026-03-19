# Spring AI Skills Demo

一个 Spring Boot 示例项目，展示如何使用 **Spring AI Skills 渐进式披露**机制构建 AI Agent。

核心思路：LLM 初始只看到简短的技能目录（Level 1 元数据），需要时再调用 `loadSkill` 按需加载完整 API 指令（Level 2），对于复杂的分层 Skill（如 OpenAPI 规范生成的），可进一步调用 `readSkillReference` 按需读取具体操作/资源文档（Level 3），相比一次性注入完整规范可减少 60-90% 的 token 消耗。

## 功能特性

- **渐进式披露 Skills** - 三级加载机制：目录 → 技能文档 → 参考文件
- **分层 Skill 结构** - 支持 OpenAPI 规范生成的复杂技能
- **PetStore Mock 后端** - 完整的 Swagger PetStore API 示例实现
- **会话记忆系统** - 基于 H2 数据库的持久化对话记忆，支持多会话隔离
- **确认模式** - 变更操作需用户手动确认后才执行
- **OkHttp 重试机制** - 处理 LLM API 的间歇性网络问题
- **🆕 CopilotKit 前端集成** - 现代化的 Next.js 15 + React 19 前端，支持 AG-UI 协议

## 技术栈

| 组件 | 版本 |
|------|------|
| Spring Boot | 3.4.2 |
| Spring AI | 1.1.0 |
| Java | 17+ |
| springdoc-openapi | 2.6.0 |
| OkHttp | 4.12.0 |
| H2 Database | 2.3.232 |
| Maven | 3.8+ |

兼容任何 OpenAI API 兼容的 LLM 服务（OpenAI、DeepSeek 等）。

## 架构

### 双前端架构

项目现在支持两种前端方式：

```
┌─────────────────────────────────────────────────────────────┐
│  方式 1: 传统 Web 界面（原有）                                 │
│  http://localhost:8080                                       │
│  └── Thymeleaf 模板 + 静态资源                                │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  方式 2: CopilotKit 现代前端（新增）🆕                         │
│  http://localhost:3001                                       │
│  ├── Next.js 15 + React 19                                   │
│  ├── CopilotPopup 聊天组件                                    │
│  ├── CopilotRuntime BFF 层                                   │
│  └── HttpAgent 连接到 Java 后端                               │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│  Java 后端 (Spring AI)                                       │
│  ├── /api/chat      - 传统聊天端点（旧前端）                   │
│  ├── /api/agui      - AG-UI 协议端点（新前端）🆕               │
│  ├── /api/products  - 商品 REST API                          │
│  └── /api/v3/*      - PetStore Mock API                      │
└─────────────────────────────────────────────────────────────┘
```

### 请求流程（传统前端）

```
用户消息
  │
  ▼
ChatController (POST /api/chat)
  │
  ▼
AgentService.chat()
  ├── 重置已加载技能（无状态）
  │
  ▼
MessageChatMemoryAdvisor
  ├── 从 H2 数据库加载历史消息
  │
  ▼
SkillsAdvisor (CallAdvisor, HIGHEST_PRECEDENCE)
  ├── 注入 System Prompt：
  │   ├── Level 1: 所有技能名称 + 描述（来自 SkillRegistry）
  │   └── Level 2: 当前对话已加载技能的完整指令
  │
  ▼
LLM 决策
  ├── 调用 SkillTools.loadSkill(name)       → 获取技能概述 + 参考文件索引
  ├── 调用 SkillTools.readSkillReference()  → 按需读取具体操作/资源/Schema 文档
  ├── 调用 SkillTools.httpRequest(...)      → 请求 REST API（或返回确认请求）
  │
  ▼
MessageChatMemoryAdvisor
  ├── 将对话保存到 H2 数据库
  │
  ▼
返回结果给用户（确认模式下，变更操作返回确认请求由前端执行）
```

### 三级渐进式披露

| Level | 工具 | 内容 | 示例 |
|-------|------|------|------|
| 1 | System Prompt | 技能目录（名称+描述） | `swagger-petstore-openapi-3-0: Pet Store API` |
| 2 | `loadSkill` | 技能概述 + 参考文件索引 | SKILL.md（含 resources/operations/schemas 目录结构） |
| 3 | `readSkillReference` | 具体操作/资源文档 | `operations/findPetsByStatus.md`, `schemas/Pet.md` |

### 核心组件

| 组件 | 职责 |
|------|------|
| `agent/SkillRegistry` | 启动时读取 `resources/skills/*/SKILL.md`，解析 YAML frontmatter + Markdown body |
| `agent/SkillTools` | `@Tool` 方法：`loadSkill`（加载技能）、`readSkillReference`（读取参考文件）、`httpRequest`（HTTP 调用） |
| `agent/SkillsAdvisor` | `CallAdvisor`，构建含 Level 1 目录 + Level 2 已加载内容的 System Prompt |
| `service/AgentService` | 编排 `ChatClient` + Advisor + Tools + ChatMemory |
| `service/ProductService` | 内存商品目录和购物车（无数据库），预置 5 条示例数据 |
| `petstore/*` | PetStore Mock 后端（Controller + Service + Model） |
| `config/SpringAiConfig` | OkHttp 配置，含响应体缓冲和 EOFException 重试机制 |

### 会话记忆系统

基于 Spring AI 的 `JdbcChatMemoryRepository` + H2 文件数据库实现：

- **持久化存储**：对话历史保存在 `./data/chat-memory.mv.db`
- **消息窗口**：保留最近 20 条消息，防止上下文过长
- **多会话隔离**：通过 `conversationId` 区分不同会话
- **自动配置**：使用 `spring-ai-starter-model-chat-memory-repository-jdbc` 自动配置

```java
// AgentService.java
ChatMemory chatMemory = MessageWindowChatMemory.builder()
        .chatMemoryRepository(jdbcChatMemoryRepository)
        .maxMessages(20)
        .build();

this.chatClient = builder
    .defaultAdvisors(
        skillsAdvisor,
        MessageChatMemoryAdvisor.builder(chatMemory).build()
    )
    .build();
```

### Skills 格式

每个技能位于 `src/main/resources/skills/<skill-name>/SKILL.md`：

#### 简单技能（单文件）

```yaml
---
name: search-products
description: 搜索商品目录，支持关键词、分类、价格范围过滤
version: 1.0
links:
  - name: get-product-detail
    description: 获取单个商品的详细信息
---

# 商品搜索技能
## API 端点
GET /api/products
...
```

#### 分层技能（OpenAPI 生成）

```
skills/swagger-petstore-openapi-3-0/
├── SKILL.md                    # Level 2: 技能概述 + 目录索引
└── references/                 # Level 3: 参考文件
    ├── authentication.md       # 认证说明
    ├── resources/              # 资源文档
    │   ├── pet.md
    │   ├── store.md
    │   └── user.md
    ├── operations/             # 操作文档（19个）
    │   ├── findPetsByStatus.md
    │   ├── getPetById.md
    │   └── ...
    └── schemas/                # Schema 文档（6个）
        ├── Pet/Pet.md
        ├── Order/Order.md
        └── ...
```

LLM 工作流程：
1. 看到 `swagger-petstore-openapi-3-0` 目录条目
2. 调用 `loadSkill("swagger-petstore-openapi-3-0")` 获取概述
3. 根据需要调用 `readSkillReference("swagger-petstore-openapi-3-0", "operations/findPetsByStatus.md")` 获取详细文档
4. 调用 `httpRequest` 执行 API 操作

技能通过 `links` 形成有向图，支持链式发现：`search-products → get-product-detail → add-to-cart → checkout`。

## 快速开始

### 前置要求

- JDK 17+
- Maven 3.8+
- OpenAI 兼容 API Key（OpenAI、DeepSeek 等）

### 配置

在项目根目录创建 `.env` 文件（已在 `.gitignore` 中排除）：

```bash
OPENAI_API_KEY=your-api-key
OPENAI_BASE_URL=https://api.deepseek.com   # 或 https://api.openai.com
OPENAI_MODEL=deepseek-chat                  # 或 gpt-4o
```

### 构建与运行

```bash
# 加载环境变量
set -a && source .env && set +a 
# set -a 用于开启allexport模式后加载.env，确保变量导出给子进程（Spring Boot应用）
# source .env 加载环境变量
# set +a 关闭allexport模式
#
# 另一种常见写法：
# export $(cat .env | grep -v '^#' | xargs) 

# 构建
mvn clean package -DskipTests

# 如果需要重启服务，可以这样精准杀死在某个端口上运行的服务端进程：
# lsof -ti:8080 -sTCP:LISTEN | xargs -r kill -9 2>/dev/null; echo "Killed server on port 8080"; sleep 1; lsof -ti:8080 || echo "Port 8080 is free"

# 运行
mvn spring-boot:run -DskipTests
```

### 访问地址

| 功能 | URL |
|------|-----|
| 聊天界面（传统）| http://localhost:8080 |
| **CopilotKit 前端** 🆕 | http://localhost:3001 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| H2 控制台 | http://localhost:8080/h2-console |

H2 控制台连接信息：
- JDBC URL: `jdbc:h2:file:./data/chat-memory`
- 用户名: `sa`
- 密码: (空)

### 运行 CopilotKit 前端 🆕

前端项目位于 `frontend/` 目录：

```bash
# 安装依赖
cd frontend
npm install

# 运行开发服务器
npm run dev

# 构建生产版本
npm run build
npm start
```

前端环境变量配置（`frontend/.env.local`）：
```bash
JAVA_BACKEND_URL=http://localhost:8080
```

**关键特性**：
- ✅ 实时流式响应（SSE）
- ✅ 会话记忆（与 Java 后端共享）
- ✅ 工具调用（loadSkill + httpRequest）
- ✅ OpenAPI 技能解析（宠物商店功能已验证）
- ✅ 现代化 UI（Tailwind CSS）
- ✅ 深色模式支持

**技术栈**：
- Next.js 15.1.6
- React 19
- CopilotKit 1.3.14
- @ag-ui/client 0.0.47
- Tailwind CSS 3.4.1

## 测试验证

以下是经过验证的测试步骤，可完整复现。

### 1. REST API 测试

启动应用后，验证商品 API 是否正常：

```bash
# 查询全部商品
curl -s http://localhost:8080/api/products
```

预期返回 5 条商品数据（iPhone 15、华为 MatePad Pro、Sony WH-1000XM5、小米电视 65寸、MacBook Air M3）。

```bash
# 按关键词搜索
curl -s "http://localhost:8080/api/products?keyword=耳机&priceMax=3000"
```

预期仅返回 Sony WH-1000XM5。

```bash
# 查看商品详情
curl -s http://localhost:8080/api/products/3
```

```bash
# 加入购物车
curl -s -X POST "http://localhost:8080/api/products/cart?userId=1&productId=3"
```

预期返回 `{"success":true,"message":"已添加到购物车","cartSize":1}`。

```bash
# 结算
curl -s -X POST "http://localhost:8080/api/products/checkout?userId=1"
```

预期返回 `{"success":true,"message":"订单已提交","totalAmount":2499.0,"itemCount":1}`。

### 2. Agent 聊天测试

测试完整的 Agent 流程（LLM → loadSkill → httpRequest → 回复）：

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"帮我找一款3000元以下的耳机"}' \
  --max-time 60
```

预期行为：
1. Agent 调用 `loadSkill("search-products")` 加载搜索技能
2. Agent 调用 `httpRequest(GET /api/products?priceMax=3000)` 或类似请求
3. 返回包含 Sony WH-1000XM5（2499 元）的推荐回复

```bash
# 测试完整购物流程
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"把 Sony WH-1000XM5 加入购物车并结算"}' \
  --max-time 60
```

预期行为：Agent 会依次加载 `add-to-cart` 和 `checkout` 技能，完成加购和结算。

### 3. 会话记忆测试

测试 Agent 是否能记住上下文信息：

```bash
# 第一次对话：告诉 Agent 你的名字
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"你好，我叫张三", "conversationId": "memory-test-001"}' \
  --max-time 60
```

```bash
# 第二次对话：询问 Agent 是否记得你的名字
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"你记得我叫什么名字吗？", "conversationId": "memory-test-001"}' \
  --max-time 60
```

预期行为：Agent 应该回复记得你叫"张三"。

验证数据库持久化：
```bash
# 检查 H2 数据库文件
ls -la ./data/chat-memory.mv.db
```

预期结果：数据库文件存在且有内容（约 40KB+）。

多会话隔离测试：
```bash
# 使用不同的 conversationId，Agent 不会记住之前的信息
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"你记得我叫什么名字吗？", "conversationId": "memory-test-002"}' \
  --max-time 60
```

预期行为：Agent 会表示不记得（因为这是新会话）。

### 4. 确认模式测试

以 `confirm-before-mutate=true` 启动应用后，Agent 对变更操作（POST/PUT/DELETE）不会直接执行，而是返回包含 `` ```http-request `` 代码块的确认请求：

```bash
# 以确认模式启动
mvn spring-boot:run -DskipTests '-Dspring-boot.run.arguments=--app.confirm-before-mutate=true'

# 发送加购请求
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"请把商品ID为3的商品加入购物车（用户ID=1）"}' \
  --max-time 90
```

预期行为：
1. Agent 不直接执行加购操作
2. 返回操作说明 + `` ```http-request `` 代码块（含 method、url、params 等元数据）
3. 前端展示说明文本和「确定」/「取消」按钮
4. 用户点击「确定」后，前端根据元数据构造 HTTP 请求并发送到后端 API

### 5. Web 界面测试

在浏览器打开 http://localhost:8080 ，在聊天框中输入自然语言，如：

- "找一款3000元以内的耳机"
- "第一个商品的详细信息"
- "把它加入购物车"
- "帮我结算"

开启确认模式后，加购和结算等变更操作会显示确认按钮，需用户手动确认后才执行。

### 自动化测试

项目包含回归测试脚本，覆盖 REST API、Agent 聊天、确认模式（前端执行）等场景：

```bash
# 主测试脚本（购物助手场景）- 21 个测试用例
./test.sh

# PetStore 测试脚本（分层 Skill 场景）- 13 个测试用例
./test-petstore.sh
```

测试脚本会自动启动应用、运行全部测试用例，最后输出通过/失败汇总。

### 已验证环境

- macOS + JDK 17+ + Maven 3.9
- DeepSeek API (`deepseek-chat` 模型)
- Spring AI 1.1.0 + Spring Boot 3.4.2

## 项目结构

```
spring-ai-skills-demo/
├── src/main/java/com/example/demo/
│   ├── agent/
│   │   ├── SkillRegistry.java      # 技能注册表
│   │   ├── SkillTools.java         # @Tool 方法
│   │   └── SkillsAdvisor.java      # System Prompt 构建
│   ├── config/
│   │   ├── SpringAiConfig.java     # OkHttp + 重试配置
│   │   ├── AgUiConfig.java         # AG-UI 配置 🆕
│   │   └── CorsConfig.java         # 跨域配置 🆕
│   ├── controller/
│   │   ├── ChatController.java     # 聊天 API
│   │   ├── ProductController.java  # 商品 REST API
│   │   └── AgUiController.java     # AG-UI 端点 🆕
│   ├── petstore/                   # PetStore Mock 后端
│   │   ├── PetController.java
│   │   ├── StoreController.java
│   │   ├── UserController.java
│   │   ├── PetStoreService.java
│   │   └── model/
│   ├── service/
│   │   ├── AgentService.java       # 集成 ChatMemory
│   │   └── ProductService.java
│   └── model/
│
├── src/main/java/com/agui/         # AG-UI SDK 实现 🆕
│   ├── core/                       # 核心接口和事件
│   │   ├── agent/                  # Agent 接口
│   │   ├── event/                  # 事件类型（19个）
│   │   ├── message/                # 消息类型（6种）
│   │   ├── tool/                   # 工具定义
│   │   └── stream/                 # 事件流
│   ├── server/                     # 服务端实现
│   │   ├── spring/                 # Spring 集成
│   │   └── streamer/               # SSE 流输出
│   └── spring/ai/                  # Spring AI 集成
│       └── SpringAIAgent.java      # Agent 实现
│
├── frontend/                       # CopilotKit 前端 🆕
│   ├── app/
│   │   ├── api/copilotkit/
│   │   │   └── route.ts            # BFF 层（HttpAgent）
│   │   ├── layout.tsx              # CopilotKit Provider
│   │   ├── page.tsx                # 主页面
│   │   └── globals.css             # 样式
│   ├── package.json                # 依赖管理
│   ├── next.config.js              # Next.js 配置
│   ├── tailwind.config.js          # Tailwind CSS
│   └── README.md                   # 前端文档
│
├── src/main/resources/
│   ├── skills/
│   │   ├── search-products/SKILL.md
│   │   ├── get-product-detail/SKILL.md
│   │   ├── add-to-cart/SKILL.md
│   │   ├── checkout/SKILL.md
│   │   └── swagger-petstore-openapi-3-0/   # 分层技能示例
│   │       ├── SKILL.md
│   │       └── references/
│   │           ├── operations/
│   │           ├── resources/
│   │           └── schemas/
│   ├── petstore.yaml              # OpenAPI 3.0 规范
│   └── application.yml
│
├── data/                          # H2 数据库文件（自动创建）
│   ├── chat-memory.mv.db          # 对话记忆数据库
│   └── chat-memory.trace.db       # 数据库跟踪日志
│
├── docs/drafts/                   # 文档草稿 🆕
│   ├── enterprise-agent-frontend-guide-v4.md  # 前端集成指南
│   └── spring-ai-agui-guide.md    # AG-UI 集成指南
│
├── COPILOTKIT_INTEGRATION.md      # CopilotKit 集成文档 🆕
└── TEST_REPORT.md                 # 测试报告 🆕
```

## 升级说明

从 Spring AI 1.0.0-M6 升级到 1.1.0 的主要变更：

| 变更类型 | M6 | 1.1.0 |
|---------|-----|-------|
| Artifact 命名 | `spring-ai-openai-spring-boot-starter` | `spring-ai-starter-model-openai` |
| Advisor API | `CallAroundAdvisor` | `CallAdvisor` |
| Request/Response | `AdvisedRequest`/`AdvisedResponse` | `ChatClientRequest`/`ChatClientResponse` |
| Chat Memory 包名 | `org.springframework.ai.chat.memory.jdbc` | `org.springframework.ai.chat.memory.repository.jdbc` |
| Chat Memory Starter | 无 | `spring-ai-starter-model-chat-memory-repository-jdbc` |
