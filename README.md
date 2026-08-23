# Spring AI Skills Demo

一个 Spring Boot + Spring AI 示例项目，展示 Agent 如何通过 Skills 渐进式披露按需加载业务 API 指令，并结合 AG-UI/CopilotKit、多模态输入、会话记忆和 RAG。

## 项目能力

- Level 1/2/3 Skills：技能目录 -> `SKILL.md` -> 分层 references。
- 商品搜索、详情、购物车和结算 API。
- Swagger Petstore Mock API 和 OpenAPI 分层 Skill。
- 普通聊天、SSE 流式聊天、图片理解、音频转写。
- Next.js + CopilotKit v2 前端，经 AG-UI SSE 连接 Java Agent。
- JDBC 对话记忆、VectorStoreChatMemoryAdvisor 和知识库问答。
- OpenAI-compatible、Anthropic-compatible、MiniMax Provider 切换。

## 常见扩展入口

| 我想做什么 | 从哪里开始 |
|---|---|
| 添加公司保修、服务条款、配送政策等知识，让 Agent 基于文档回答 | [知识库与运行时 Skills：通过知识库提供知识](docs/knowledge-and-skills.md#路径一通过知识库提供知识) |
| 新增查询订单、创建售后单、申请退款等可执行服务 | [知识库与运行时 Skills：通过运行时 Skills 提供服务](docs/knowledge-and-skills.md#路径二通过运行时-skills-提供服务) |
| 判断 Spring AI 是否已经内置同类 Skills 能力 | [与 Spring AI 最新能力的关系](docs/knowledge-and-skills.md#与-spring-ai-最新能力的关系) |

## 快速开始

### 前置条件

- JDK 17+
- Maven 3.8+
- 一个可用的模型 API 配置
- 使用 PostgreSQL profile 时，需要 PostgreSQL 和 `vector` 扩展

### 配置

```bash
cp .env.example .env
# 编辑 .env，至少填写模型 API 密钥；不要提交 .env
set -a && source .env && set +a
```

默认使用 OpenAI-compatible 配置。完整变量、Provider 和数据库说明见
[配置参考](docs/configuration.md)。

### 后端构建和运行

```bash
# 编译、打包，不调用外部 LLM
mvn -DskipTests clean package

# 启动后端，默认端口 8080
mvn spring-boot:run -DskipTests
```

当前 `src/main/resources/application.yml` 显式激活 `postgresql` profile。没有 PostgreSQL 时，显式选择非 PostgreSQL profile 使用 H2 和 `SimpleVectorStore`：

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -DskipTests
```

### 前端

```bash
cd frontend
npm ci
npm run dev
```

前端开发服务器使用 `http://localhost:4000`，需要后端同时运行在 `http://localhost:8080`。前端 BFF 和浏览器 HTTP 工具的地址变量分别是 `JAVA_BACKEND_URL` 与 `NEXT_PUBLIC_JAVA_BACKEND_URL`。

## 主要地址

| 功能 | 地址 |
|---|---|
| Spring Boot 静态页面 | `http://localhost:8080/` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` |
| AG-UI 健康检查 | `http://localhost:8080/api/agui/health` |
| AG-UI Agent 信息 | `http://localhost:8080/api/agui/info` |
| Next.js/CopilotKit 前端 | `http://localhost:4000/` |
| H2 控制台 | `http://localhost:8080/h2-console`，仅非 `postgresql` profile 有意义 |

## 架构概要

普通聊天链路：

```text
POST /api/chat
  -> ChatController
  -> AgentService
  -> SkillsAdvisor + 记忆/RAG Advisors
  -> ChatClient/ChatModel
  -> SkillTools 或业务 API
```

CopilotKit 链路：

```text
CopilotKit v2 UI
  -> frontend/app/api/copilotkit/route.ts
  -> POST /api/agui
  -> AgUiController
  -> SpringAIAgent
  -> Spring AI ChatModel
```

AG-UI 模式下，后端只注册 `loadSkill` 和 `readSkillReference`；浏览器侧唯一注册 `httpRequest`。GET 自动执行，写操作通过 `useHumanInTheLoop` 请求用户确认后执行，并从浏览器存储的 Demo token 发送 `Authorization`。

详细模块关系、工具边界和数据流见 [架构说明](docs/ARCHITECTURE.md)。

## API 入口

| 领域 | 入口 |
|---|---|
| 聊天、多模态、SSE | `POST /api/chat`、`/api/chat/stream`、`/api/chat/multimodal/stream` |
| 音频转写 | `POST /api/transcribe/stream` |
| Demo 认证 | `/api/auth/login`、`/api/auth/verify` |
| 商品 | `/api/products/**` |
| PetStore Mock | `/api/v3/pet/**`、`/api/v3/store/**`、`/api/v3/user/**` |
| AG-UI | `/api/agui` |
| 结果解释 | `POST /api/explain-result` |

详细方法、参数、认证和 SSE 形状见 [REST 与 SSE API 参考](docs/rest-api.md)；业务 API 的模型指令以 `src/main/resources/skills/` 为准。

## 测试和验证

```bash
# 文档改动
git diff --check

# 后端编译
mvn -DskipTests clean package

# Java 测试，可能访问外部 LLM
mvn test

# 前端构建
cd frontend
npm run build
```

专项 Shell、AG-UI、RAG、流式、多模态和 Playwright 验证见 [验证手册](docs/HARNESS.md)。这些测试通常需要后端、`.env`、外部 Provider、PostgreSQL 或测试媒体文件，不能默认视为离线测试。

## Docker

仓库包含 `Dockerfile` 和 `docker-compose.yml`，但 Compose 中仍保留部分历史的 H2、确认模式和 Embedding 默认值。使用 Docker 前请按 [开发指南](docs/DEVELOPMENT.md) 和 [配置参考](docs/configuration.md) 核对 `SPRING_PROFILES_ACTIVE`、数据库和环境变量，不要把 Compose 注释当作当前应用默认值。

## 文档导航

| 文档 | 用途 |
|---|---|
| [AGENTS.md](AGENTS.md) | Agent 使用的唯一当前状态导航 |
| [CLAUDE.md](CLAUDE.md) | 兼容入口，跳转到 `AGENTS.md` |
| [docs/README.md](docs/README.md) | 文档体系总索引 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 系统架构和 Agent 工具边界 |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | 本地开发与启动 |
| [docs/configuration.md](docs/configuration.md) | 配置和外部依赖 |
| [docs/rest-api.md](docs/rest-api.md) | REST/SSE 端点参考 |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | 可复制的 REST、聊天、RAG、多模态和 Docker 示例 |
| [docs/HARNESS.md](docs/HARNESS.md) | 验证和测试矩阵 |
| [docs/troubleshooting.md](docs/troubleshooting.md) | 故障排查 |
| [docs/knowledge-and-skills.md](docs/knowledge-and-skills.md) | 知识库文档、运行时 Skills 和 Spring AI 能力边界 |
| [frontend/README.md](frontend/README.md) | 前端开发入口 |
| [TEST_REPORT.md](TEST_REPORT.md) | 带日期的历史测试记录 |

## 当前边界

- 认证用户和密码写在 `AuthService`，Token 是 Base64 Demo token，不是生产 JWT。
- `ProductService` 和 `PetStoreService` 使用内存数据；数据库主要用于聊天记忆和向量存储。
- `ag-ui-4j/` 是独立 Git 子模块；主仓库还维护了一份复制到 `src/main/java/com/agui/` 的源码。
- `docs/drafts/` 保存计划、诊断和历史方案，使用前必须回到源码核对。
- `.agents/skills/project-docs/` 是 Agent 工作流包，不是运行时 Skills 目录。
