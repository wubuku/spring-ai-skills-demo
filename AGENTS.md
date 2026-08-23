# AGENTS.md

本文件是仓库级协作说明的唯一事实来源。根目录 `CLAUDE.md` 只是兼容入口，不在其中重复维护项目规则。

`ag-ui-4j/` 是独立 Git 子模块。进入该目录工作时，优先遵循子模块自己的 `CLAUDE.md`；本文只描述主仓库及主仓库如何使用它。

## 项目定位

这是一个 Spring Boot 示例应用，展示 Spring AI Skills 的渐进式披露，以及 AG-UI/CopilotKit、多模态输入、会话记忆和 RAG 的组合方式。

当前仓库不是只有一个聊天 Demo，主要包含：

- 商品管理 API：商品搜索、详情、购物车和结算。
- 企业助手 Agent：按需加载 Skills，再调用业务 API。
- Swagger Petstore Mock API：用于验证分层 OpenAPI Skill。
- 两条聊天链路：
  - 内置静态页面，直接访问 Spring Boot 后端。
  - `frontend/` 下的 Next.js + CopilotKit 前端，经 BFF 接入 AG-UI SSE。
- 图片理解、语音转写、流式聊天和流式转写。
- JDBC 对话记忆、VectorStoreChatMemoryAdvisor 语义记忆和 QuestionAnswerAdvisor 知识库问答。

项目语言以中文为主。新增用户界面、提示词、Swagger 描述、Skill 文档和日志说明时，保持现有中文风格。

## 技术基线

以当前源码和 `pom.xml` 为准：

- Java：Maven 编译目标为 17。
- Spring Boot：3.4.2。
- Spring AI：1.1.2。
- Maven：3.8+。
- 前端：Next.js 15.1.6、React 19、TypeScript、CopilotKit 1.60.x、Tailwind CSS 3。
- AG-UI：`ag-ui-4j` 子模块提供核心事件、Spring Server 和 Spring AI 集成源码；部分源码已复制到主仓库 `src/main/java/com/agui/`，且主仓库对 `SpringAIAgent` 有本地修改。

Dockerfile 当前使用 Amazon Corretto 21，并以 `--enable-preview` 启动；这与 Maven 的 Java 17 编译声明不是同一件事。修改 Docker 或 Java 版本时同时检查 `pom.xml`、`Dockerfile` 和 CI/部署命令，不要仅根据其中一处推断最低版本。

## 目录结构

```text
src/main/java/com/example/demo/
├── agent/       Skills 注册、提示词 Advisor、工具和 JSON 参数适配
├── auth/        Demo 认证、Filter、线程上下文透传
├── config/      Spring AI、AG-UI、Security、CORS、向量库和 HTTP 客户端配置
├── controller/  聊天、多模态、AG-UI、认证、商品和结果解释端点
├── knowledge/   启动时加载知识库文档
├── model/       请求、响应、商品和 Skill 模型
├── petstore/    Swagger Petstore Mock Controller、Service、Model
└── service/     Agent、商品、记忆、提示词、视觉和语音服务

src/main/java/com/agui/
├── core/        AG-UI 消息、事件、工具、状态和流
├── server/      本地 Agent、Spring 服务和 SSE streamer
├── spring/ai/   主仓库维护过的 SpringAIAgent 和工具回调适配
└── json/        Jackson mixin 和 ObjectMapper 工厂

src/main/resources/
├── application*.yml
├── prompts/     系统提示、模式规则、视觉和结果解释模板
├── skills/      Skill.md 及分层 Skill 的 references
├── knowledge-base/
├── petstore.yaml
└── static/index.html

frontend/
├── app/         Next.js 页面和 CopilotKit BFF route
├── components/  CopilotKit Provider、认证和消息组件
├── hooks/       浏览器侧 httpRequest 人在回路工具
└── package.json

ag-ui-4j/        Git 子模块，不要把它当作主仓库普通源码目录修改
data/            本地 H2、SimpleVectorStore 数据目录，已被 Git 忽略
docs/            集成文档、诊断记录和设计草稿
test-*.sh        端到端、回归和专项诊断脚本
```

`*.bak`、诊断脚本和 `docs/drafts/` 中有不少历史排查产物。修改功能时以当前 Java/TypeScript 源码为准，不要把草稿中的旧方案当成现行架构。

## 后端启动与构建

### 环境变量

不要提交 `.env`。参考 `.env.example` 创建本地配置，至少准备：

```bash
OPENAI_API_KEY=...
OPENAI_BASE_URL=https://api.openai.com
OPENAI_MODEL=gpt-4o
```

`app.llm.provider` 支持：

- `openai`：读取 `OPENAI_API_KEY`、`OPENAI_BASE_URL`、`OPENAI_MODEL`。
- `anthropic`：读取 `ANTHROPIC_API_KEY`、`ANTHROPIC_BASE_URL`、`ANTHROPIC_MODEL`。
- `minimax`：读取 `SPRING_AI_MINIMAX_API_KEY`、`SPRING_AI_MINIMAX_BASE_URL`、`SPRING_AI_MINIMAX_CHAT_OPTIONS_MODEL`。

可选能力还需要：

- 图片理解：`VISION_BASE_URL`、`VISION_API_KEY`、`VISION_MODEL`。
- 语音转写：`TRANSCRIPTION_BASE_URL`、`TRANSCRIPTION_API_KEY`、`TRANSCRIPTION_MODEL`。
- 向量记忆/RAG：`SILICONFLOW_API_KEY`、`SILICONFLOW_URL`、`SILICONFLOW_MODEL`、`SILICONFLOW_DIMENSIONS`。

在 zsh 中加载 `.env` 使用：

```bash
set -a && source .env && set +a
```

不要把 `export $(cat .env ... )` 作为默认操作；复杂值和 zsh 的解析会使这种写法不可靠。

### Maven 命令

```bash
# 编译、打包，不访问外部 LLM API
mvn -DskipTests clean package

# 启动后端，默认监听 8080
mvn spring-boot:run -DskipTests

# 运行全部 Maven 测试
mvn test
```

`mvn test` 当前包含 `@SpringBootTest` 的 DeepSeek/Tools 外部 API 烟测，不是完全离线的单元测试。没有有效 LLM 配置或网络时，优先使用 `mvn -DskipTests clean package` 验证编译和打包。

后端默认端口为 `8080`。应用运行后：

- 静态页面：`http://localhost:8080/`
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`
- H2 控制台：`http://localhost:8080/h2-console`，仅非 `postgresql` profile 时有意义

### 数据库与 profile

当前 `src/main/resources/application.yml` 明确设置了 `spring.profiles.active: postgresql`，因此不能再把“H2 是默认运行模式”当作现状。

- `postgresql` profile：PostgreSQL JDBC Chat Memory + PgVectorStore，需要 PostgreSQL、`vector` 扩展及对应凭证。
- 任意非 `postgresql` profile：基础配置使用文件型 H2 和 `SimpleVectorStore`，向量数据保存到 `./data/vector-store.json`。例如：

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -DskipTests
```

`application-dev.yml` 只有在显式使用 `dev` profile 时才会加载。修改数据库配置时同时检查 `application.yml`、`application-postgresql.yml`、`VectorStoreConfig` 和 `VectorStorePostgresqlConfig`。

## Agent 架构

### 普通聊天链路

`POST /api/chat` 的 JSON 请求进入 `ChatController`，然后调用 `AgentService`：

1. 清空本轮已加载的技能状态。
2. 通过 `SkillsAdvisor` 注入技能目录和本轮已经加载的 Skill 内容。
3. 通过 `MessageChatMemoryAdvisor` 读取 JDBC 对话窗口。
4. 通过 `VectorStoreChatMemoryAdvisor` 注入语义相关历史。
5. 通过 `QuestionAnswerAdvisor` 查询知识库。
6. 使用 `SkillTools` 注册的工具执行普通链路工具调用。
7. 返回同步文本，或由 `/api/chat/stream` 以 SSE 流式返回。

普通链路的 `AgentService` 使用 `MessageWindowChatMemory(maxMessages=20)`。

### AG-UI/CopilotKit 链路

`frontend/` 的请求路径是：

```text
CopilotKit UI
  -> frontend/app/api/copilotkit/route.ts
  -> POST /api/agui
  -> AgUiController
  -> ag-ui-4j AgUiService / AgentStreamer
  -> SpringAIAgent
  -> Spring AI ChatModel
```

AG-UI 的 `AgUiConfig` 有意只注册后端核心工具：

- `loadSkill`
- `readSkillReference`

AG-UI 不向模型注册后端 `httpRequest` 或 `buildHttpRequest`。浏览器侧 `frontend/hooks/useHttpRequestTool.tsx` 注册同名 `httpRequest`，从 `localStorage` 读取认证 token，在浏览器中执行请求；GET 自动执行，POST/PUT/DELETE/PATCH 进入用户确认流程。

`SpringAIAgent` 当前关闭 Spring AI 的内部工具执行，手动执行后端可执行工具，并等待前端工具通过 CopilotKit 返回结果。它还负责：

- 合并后端和前端工具 schema。
- 通过工具名避免重复注册。
- 保存前端工具调用/结果到 AG-UI 会话记忆。
- 限制单次 run 的工具调用次数，当前 `maxToolCalls=5`。
- 对 `httpRequest` URL 做后端校验，避免模型猜错端点导致循环。
- 使用 `JsonArgToolCallback` 绕过 Spring AI 1.1.2 的方法参数 JSON 反序列化问题。

AG-UI Agent 的 `MessageWindowChatMemory` 当前只保留最近 4 条消息。不要把它和普通 `AgentService` 的 20 条窗口混为一谈。

### Skills 渐进式披露

`SkillRegistry` 启动时扫描 `classpath:skills/*/SKILL.md`，解析 YAML frontmatter 和 Markdown body，并建立 API 索引：

- Level 1：`SkillsAdvisor` 注入所有 Skill 的名称和描述。
- Level 2：`loadSkill` 返回某个 Skill 的完整 `SKILL.md` 内容及关联 Skill 提示。
- Level 3：分层 Skill 使用 `readSkillReference` 读取 `references/` 下的单个资源、操作或 schema 文档。

当前 Skill 包括：

- `search-products`
- `get-product-detail`
- `add-to-cart`
- `view-cart`
- `checkout`
- `swagger-petstore-openapi-3-0`

添加或修改 Skill 时：

1. 保持 `src/main/resources/skills/<name>/SKILL.md` 的 frontmatter 格式。
2. API 路径必须以实际 Controller 为准。
3. 关联 Skill 使用 `links`，不要把所有文档一次性塞入系统提示。
4. OpenAPI 分层 Skill 继续使用 `references/resources`、`references/operations` 和 `references/schemas`。
5. 不要在提示词里硬编码业务 URL；模型应从 Skill 文档获取路径，前后端 API index 负责校验。

## REST 与流式端点

| 方法和路径 | 说明 |
|---|---|
| `POST /api/auth/login` | Demo 登录，返回 token |
| `GET /api/auth/verify` | 验证 `Authorization` |
| `POST /api/chat` + JSON | 普通同步聊天 |
| `POST /api/chat` + multipart | 图片/音频同步多模态聊天 |
| `POST /api/chat/text` + JSON | 纯文本兼容端点 |
| `POST /api/chat/stream` + JSON | 普通文本 SSE |
| `POST /api/chat/multimodal/stream` + multipart | 图片、音频和文本 SSE |
| `POST /api/transcribe/stream` + multipart | 纯音频 SSE 转写，字段名为 `audio` |
| `POST /api/agui` | CopilotKit/AG-UI SSE 端点 |
| `GET /api/agui/health` | AG-UI 健康检查 |
| `GET /api/agui/info` | Agent 元信息 |
| `GET /api/agui/skills/api-index` | Skill API 索引，供前端 URL 校验 |
| `POST /api/explain-result` | 解释前端刚执行的 API 结果 |
| `GET /api/products` | 公开商品搜索 |
| `GET /api/products/{id}` | 公开商品详情 |
| `GET /api/products/cart` | 当前用户购物车，需要认证 |
| `POST /api/products/cart?productId=...` | 加入购物车，需要认证 |
| `POST /api/products/checkout` | 结算购物车，需要认证 |
| `/api/v3/pet/**` | PetStore Pet API |
| `/api/v3/store/**` | PetStore Store API |
| `/api/v3/user/**` | PetStore User API |

`POST /api/chat` 由 `Content-Type` 区分 JSON 和 multipart 两种处理器。修改端点时必须同步更新 Controller、Skill 文档、API index、提示词和回归脚本。

## 认证与安全边界

这是演示认证，不是生产认证：

- 用户和密码硬编码在 `AuthService`：`user1/password1`、`user2/password2`、`admin/admin123`。
- 当前 token 是简单的 Base64 两段字符串，不是 JWT，不提供签名、过期时间或可靠的完整性保护。
- 前端把 token 放在 `localStorage`，CopilotKit BFF 和浏览器侧 `httpRequest` 会透传 `Authorization`。
- `AuthFilter` 将认证放入 `SecurityContextHolder` 和 `UserContextHolder`。
- `ReactorBoundedElasticHookConfig` 在 Reactor/boundedElastic 线程间传递用户上下文。

不要在新代码中把这个 token 称为 JWT，也不要把它作为生产认证方案。修改跨线程认证时，重点检查 `AuthFilter`、`UserContextHolder`、`ReactorBoundedElasticHookConfig`、`AgUiController` 和 `SkillTools.extractJwt()` 的交互。

当前写操作的用户确认主要由 CopilotKit 前端 `useHumanInTheLoop` 实现。`app.confirm-before-mutate` 已不是当前配置项；不要依据旧文档恢复该模式。

## 记忆、向量库与知识库

- JDBC Chat Memory 使用 `JdbcChatMemoryRepository`。
- 非 PostgreSQL profile 使用 H2 文件 `./data/chat-memory.mv.db`。
- `KnowledgeBaseInitializer` 默认加载 `classpath:knowledge-base/*.md`。
- 可通过 `KNOWLEDGE_BASE_PATHS` 传入逗号分隔的 classpath/file glob。
- `VectorStoreChatMemoryAdvisor` 提供语义历史检索。
- `QuestionAnswerAdvisor` 使用同一个 `VectorStore` 做知识库问答。
- 非 PostgreSQL profile 使用 `SimpleVectorStore`，应用关闭时持久化到 `./data/vector-store.json`。
- PostgreSQL profile 使用 `PgVectorStore`，表名默认 `vector_store`，维度默认 1024，索引默认 HNSW，距离默认余弦距离。

向量功能依赖嵌入模型。修改 embedding URL 时注意 `SILICONFLOW_URL` 不应带 `/v1`，因为 `OpenAiEmbeddingModel` 会追加路径。

## 前端开发

当前前端实际端口以 `frontend/package.json` 为准：

```bash
cd frontend
npm ci
npm run dev
```

开发服务器默认是 `http://localhost:4000`。`frontend/README.md` 和历史根 README 中仍有 `3000`/`3001` 的旧描述，不要据此改端口。

前端后端地址：

- `JAVA_BACKEND_URL`：Next.js BFF 在服务端访问 Java 后端。
- `NEXT_PUBLIC_JAVA_BACKEND_URL`：浏览器侧登录和 `httpRequest` 访问 Java 后端。
- 两者默认都是 `http://localhost:8080`。

CopilotKit 使用 v2 `CopilotKitProvider` 和 `useSingleEndpoint`。不要把 v1 的 `useCopilotAction.renderAndWaitForResponse` 方案重新引入。

当前工作区存在但未被 Git 跟踪的前端辅助文件：

- `frontend/scripts/transform-v2-css.mjs`
- `frontend/patches/copilotkit-v2-v3.css`
- `frontend/patches/stubs/mermaid-core-stub.mjs`

`package.json` 的 `postinstall` 和 `next.config.js` 会引用其中部分文件；由于根 `.gitignore` 忽略了 `frontend/scripts/` 和 `frontend/patches/`，全新 clone 后直接 `npm ci` 可能缺少这些文件。运行前先检查它们是否存在，修改前端构建链时也要处理这个可复现性问题。

## 测试与验证

Java 单元/集成测试：

```bash
mvn test
```

常用回归脚本：

```bash
./test.sh                    # 商品、聊天、认证和基础回归
./test-petstore.sh           # 分层 Swagger Petstore Skill
./test-vector-store-memory.sh
./test-rag-knowledge-base.sh
./test-multimodal.sh
./test-streaming.sh
./test-streaming-transcribe.sh
./test-jwt-get.sh
./test-agui-jwt-full.sh
./test-sse-jwt.sh
```

专项脚本通常要求后端已经启动、`.env` 已配置，部分脚本会自行启动/停止端口 8080。多模态脚本还需要 `TEST_IMAGE_PATH`、`TEST_AUDIO_PATH`。AG-UI 浏览器测试 `test-e2e-frontend.py` 需要先启动 `frontend` 的 4000 端口，并安装 Playwright。

测试脚本和现有 `src/test` 都可能访问真实 LLM、视觉、ASR、Embedding 或 PostgreSQL。报告失败时先区分：

- 编译/应用上下文失败。
- 外部服务凭证或网络失败。
- 数据库/profile 未匹配。
- 真实 Agent/前端行为失败。

不要把一次外部 API 烟测失败直接归因于业务代码。

## AG-UI 子模块与同步边界

`.gitmodules` 将 `ag-ui-4j` 固定到外部仓库的 gitlink。当前根仓库通过复制源码的方式使用它：

- `ag-ui-4j/packages/core` 对应主仓库 `src/main/java/com/agui/core`。
- `ag-ui-4j/packages/server` 对应主仓库 `src/main/java/com/agui/server`。
- `ag-ui-4j/servers/spring` 提供主仓库使用的 Spring Server 类。
- `ag-ui-4j/integrations/spring-ai` 提供原始 Spring AI 集成参考。
- 主仓库的 `src/main/java/com/agui/spring/ai/SpringAIAgent.java` 已有本地 URL 校验、前端工具等待、调用次数限制等修改，不能简单覆盖。

`update-ag-ui.sh` 负责更新子模块指针。`sync-ag-ui.sh` 中的部分源路径与当前子模块目录布局不一致，属于历史同步脚本；运行前必须先审阅路径和 diff，不能盲目执行。

修改 AG-UI 复制源码时：

1. 先确认改动应落在主仓库、子模块还是两处。
2. 保留主仓库对 `SpringAIAgent` 的本地行为。
3. 检查 `git submodule status` 和 `git status --ignore-submodules=none`。
4. 不要把子模块内部未提交改动混入主仓库提交。

## 修改约定

- 先用 `rg`、`rg --files` 和 `git log` 定位事实，再编辑。
- 小范围手工修改使用 `apply_patch`；不要用 shell 重定向覆盖源文件。
- 保持现有包名、Spring Bean 名称、端点和配置键，除非任务明确要求迁移。
- 提示词、Skill 文档、前端工具 schema 和后端工具执行逻辑必须一起检查。
- 涉及 API 路径时，同时检查 `SkillRegistry` API index 和前端 `validateAndCorrectUrl`。
- 不要提交 `.env`、`data/`、`target/`、`node_modules/`、Playwright 截图和调试产物。
- 提交前至少运行 `mvn -DskipTests clean package`，并检查相关专项测试是否具备所需外部依赖。
- 若改动只涉及文档，也要用 `git diff --check` 和 `git status --short` 验证。
- 不要回滚用户已有的未提交改动；本次任务只应修改明确涉及的文件。
