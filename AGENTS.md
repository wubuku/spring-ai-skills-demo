# AGENTS.md

本文件是仓库级协作说明的唯一事实来源。根目录 `CLAUDE.md` 只是兼容入口，不在其中重复维护项目规则。

`ag-ui-4j/` 是独立 Git 子模块。进入该目录工作时，优先遵循子模块自己的 `CLAUDE.md`；本文只描述主仓库及主仓库如何使用它。

`spring-ai-agent-utils/` 是独立 Git 子模块，仅用于审计 Spring AI Community 的 `SkillsTool`
实现和保留可复现的参考代码。它不属于当前项目的 Maven reactor，不是运行时依赖，也不应被当作
当前项目的生产实现。当前主仓库 gitlink 固定到社区库 `v0.10.0`
（提交 `7f8bc47de1bc5a306b6cb078fa6b191ff7845572`）；对应结论见
[社区库审计报告](docs/spring-ai-agent-utils-audit.md)。

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
- Spring Boot：3.5.16。
- Spring AI：1.1.8。
- Maven：3.8+。
- 前端：Node.js 22.19+、Next.js 15.5.23、React 19、TypeScript、
  CopilotKit 1.60.2、Tailwind CSS 3。
- AG-UI：`ag-ui-4j` 子模块提供核心事件、Spring Server 和 Spring AI 集成源码；部分源码已复制到主仓库 `src/main/java/com/agui/`，且主仓库对 `SpringAIAgent` 有本地修改。

Dockerfile 当前使用 Java 17 的 Amazon Corretto 构建和运行镜像，不启用 preview。修改 Docker
或 Java 版本时同时检查 `pom.xml`、`Dockerfile` 和 CI/部署命令，不要仅根据其中一处推断最低版本。

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
spring-ai-agent-utils/
                 Git 子模块，仅用于社区 SkillsTool 参考和版本化审计
data/            本地 H2、SimpleVectorStore 数据目录，已被 Git 忽略
docs/            集成文档、诊断记录和设计草稿
.agents/skills/  可移植的 Agent Skill 包；运行时 Skills 仍在 src/main/resources/skills/
test-*.sh        端到端、回归和专项诊断脚本
```

`*.bak`、诊断脚本和 `docs/drafts/` 中有不少历史排查产物。修改功能时以当前 Java/TypeScript 源码为准，不要把草稿中的旧方案当成现行架构。

文档导航：

- [docs/README.md](docs/README.md)：稳定文档、组件入口和草稿生命周期索引。
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)：当前系统、普通 Agent 与 AG-UI 工具边界。
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)：后端/前端启动、profile 和开发顺序。
- [docs/configuration.md](docs/configuration.md)：环境变量、数据库、向量库和外部模型。
- [docs/rest-api.md](docs/rest-api.md)：Controller 端点、认证和 SSE 入口。
- [docs/OPERATIONS.md](docs/OPERATIONS.md)：REST、聊天、记忆/RAG、多模态和 Docker 操作示例。
- [docs/HARNESS.md](docs/HARNESS.md)：构建、测试和专项回归矩阵，包括普通 Agent 写操作确认的真实传统 UI E2E。
- [docs/troubleshooting.md](docs/troubleshooting.md)：按症状排查启动、模型、工具、SSE 和前端问题。
- [docs/knowledge-and-skills.md](docs/knowledge-and-skills.md)：知识库问答、运行时 Skills、扩展步骤和 Spring AI 能力边界。
- [docs/learning-path.md](docs/learning-path.md)：从 REST、Tool Calling、Skills 到记忆、RAG、SSE 和 AG-UI 的学习主线。
- [Prompt 资源与 fallback 契约规划](docs/drafts/prompt-fallback-contract-hardening-plan.md)：`PromptLoader`、SkillsAdvisor 模板和资源缺失降级的对应关系。
- [普通 Agent Advisor 与 Tool Calling 契约规划](docs/drafts/advisor-and-tool-loop-contract-hardening-plan.md)：`SkillsAdvisor` 模式提示词、Advisor 绝对顺序和 `defaultTools(skillTools)` 注册契约。
- [docs/spring-ai-agent-utils-audit.md](docs/spring-ai-agent-utils-audit.md)：与固定社区子模块版本对应的 `SkillsTool` 审计报告。
- [docs/drafts/skill-support-improvement-plan.md](docs/drafts/skill-support-improvement-plan.md)：当前项目 SKILL 支持的自包含改进规划和实施验收标准。
- [docs/drafts/backend-demo-hardening-follow-up-plan.md](docs/drafts/backend-demo-hardening-follow-up-plan.md)：后端契约、普通 Agent 写操作、SSE 和 Demo 教育性后续加固规划。
- [docs/drafts/](docs/drafts/)：计划、诊断和历史材料，不是自动可信的当前事实。

## 规划与文档评审原则

这是本仓库长期有效的工作方式，适用于需要修改代码、配置、运行时 Skill 或文档体系的实质性任务。

### 先探索，再规划，再实施

1. 在开始修改代码前，先使用 `rg`、`rg --files`、`git log` 和针对性源码阅读，确认当前实现、配置、测试和文档边界。
2. 实质性任务必须先使用 plan 工具建立并持续更新计划；计划至少包含目标、依赖、风险、验证和完成状态。
3. 先编写详尽规划文档，通常放在 `docs/drafts/`。规划应足够自包含，使任务中断后，另一位开发者可以只依靠该文档和近距离链接继续实施。
4. 规划文档不得用复制大量既有文档来制造“自包含”；应保留实施所需的当前上下文、决策和验收标准，并链接到源码、稳定指南和审计报告。
5. 对非阻断性未知项给出推荐默认值、理由和可逆边界；只有确实无法由仓库事实诚实解决的问题才保留为待讨论项。不得把核心设计留成“实施时再决定”。
6. 用户不在场时，默认主动推进：在不违反安全、权限和数据边界的前提下，采用最有把握的默认方案继续解锁后续工作，并在规划中记录该决定。

### 规划完成后的严格迭代检查

规划文档写完后，必须进行系统性检查，而不是只做格式校对：

1. 检查范围至少覆盖：规划与当前源码是否一致、实施步骤是否可行、模块边界和安全约束是否完整、测试和回滚是否足够、相关文档链接是否能发现该方案。
2. 使用一个从 `0` 开始的检查计数器。每一轮检查前和检查中按需重新阅读代码与相关文档并交叉验证。
3. 发现任何实质问题，包括事实错误、逻辑矛盾、遗漏、歧义、不可执行步骤或风险未说明时，立即修改规划文档，并将计数器重置为 `0`。
4. 只有连续完成三轮完整检查且没有对规划文档作任何修改，才能结束检查循环。措辞、格式、实施中自然会暴露的行号漂移等不影响方案正确性的细节，不强行触发重置。
5. 每一轮检查完成后，在当前任务的进度更新或最终报告中记录时间、检查范围、发现问题、处理措施和结果。无问题轮次只输出检查摘要，不修改文档，以保持“连续三轮无修改”条件可验证。
6. 检查未达到三轮连续无修改前，不得把规划标记为完成，也不得开始依赖该规划的高风险代码改造；文档整理任务本身可以在该流程内继续修复导航和表述。

### 文档与实现的对应关系

- 稳定文档记录当前事实；`docs/drafts/` 记录计划、调查、未实施方案和可追溯的评审结果。
- 任何外部实现评估必须绑定到可复现的版本、提交、子模块路径或依赖坐标，不用漂移的 `main` 作为唯一基线。
- 规划中的每个实施阶段都要指向具体源码、配置、资源和测试文件，并说明“完成后如何证明”。
- 运行时 Skills 位于 `src/main/resources/skills/`；可移植 Agent Skills 位于 `.agents/skills/`；社区子模块只作参考。三者不能在文档或实现中混称。
- `CLAUDE.md` 必须继续保持为指向本文件的兼容重定向，不在其中新增重复的 Agent 规则。

## 实施与验收原则

这些原则适用于后端、前端、运行时 Skill、API 校验和跨端联调等需要修改实现的任务。

### 先设计验收，再修改实现

1. 在规划阶段充分阅读本次改动涉及的 Java/TypeScript、配置、资源、Controller、Skill
   文档和现有脚本，明确修改范围、风险、验收证据和回滚边界。
2. 在改动生产代码前，一次性设计并编写覆盖本次行为的验收测试。优先使用尽可能端到端的
   后端集成测试；不能依赖真实 LLM、Embedding、数据库或浏览器截图来证明离线契约。
3. 前端验收至少包括 TypeScript 检查、生产构建和 Mock Playwright。前端证据只采用 DOM
   可见性与可访问状态、网络请求/响应、接口 JSON、数据库只读查询和自动化断言；截图
   不能作为验收依据。
4. 测试应覆盖本次修改的公开行为和边界，不以简单 getter 或重复 review 代替验收测试。
   外部 LLM 烟测可以补充真实链路证据，但必须与离线/Mock 质量门槛分开报告。
5. 真实 LLM 集成测试必须后置：先让 Mock/确定性测试在秒级通过，再按改动范围选择必要
   的真实模型场景。真实调用用于验证模型、工具协议和跨服务链路，不替代离线契约测试；
   需要用户明确允许时，才使用仓库 `.env` 中的本地凭证。

### 基本集成验证是硬门槛

代码检查前必须先通过与改动相关的基本验证：

```bash
mvn clean compile test-compile
mvn -Dtest='*Skill*Test,*Api*Test' test
cd frontend && npx tsc --noEmit && npm run build
```

前端核心 Mock Playwright 必须在构建后运行。若命令因外部凭证、数据库、浏览器或依赖
缺失失败，必须明确记录环境原因，不得把未验证的实现报告为完成。必要时可以使用
`.env` 中的本地凭证启动非 Mock 后端并用 `curl` 验证，但不得提交密钥。

### Mock 之后的真实 LLM 验证

真实 LLM 验证遵循固定顺序和边界：

1. 先完成本次相关的后端确定性/Mock 测试、前端构建和 Mock Playwright；Mock 未通过时
   不启动真实模型，避免把流程错误变成昂贵且缓慢的外部等待。
2. 用户允许使用本地凭证后，再选择最小但能证明本次行为的真实场景，例如 Skill
   `loadSkill`、AG-UI SSE 工具回合、API index 和最终业务端点结果。控制调用次数和
   输入规模，不把无关的多模态、RAG 或全量回归混入本次证据。
3. 启动开发服务时优先使用根目录 `dev.sh`，并记录 profile、端口和 `.env` 提供的
   数据库/模型配置；只有需要特殊隔离数据库或专项参数时，才使用显式 Maven 命令。
4. 真实调用期间必须持续观察服务日志和 SSE/HTTP 输出。发现启动失败、认证失败、
   外部凭证 401/403、工具重复循环、异常增长或服务无响应时立即停止等待，保存最小
   诊断信息并区分外部依赖失败与实现失败。
5. 非 `main` 工作区或其他分支不得复用 main 的端口、进程、测试数据库或可变数据；
   使用隔离端口、临时数据库/数据目录和独立日志。当前 main 工作区的 `.env` 只可在
   用户授权的本地验证中读取，密钥不得打印、写入日志或提交。

### 硬门槛后的三轮收敛检查

只有基本集成验证通过后，才进入固定范围、只读、互不重叠的三轮代码检查：

1. 正确性与 API 兼容：检查本次修改的行为、错误处理、现有端点和工具契约。
2. 安全与一致性：检查路径边界、URL allowlist、认证/确认边界、并发状态和数据一致性。
3. 测试与交付：检查测试是否覆盖修改、构建/启动边界、文档状态、提交范围和回滚条件。

检查阶段不得发散式探索，也不得采用“发现一个问题就临时补一个测试再全量重跑”的
循环。只修复会影响本任务正确性、成本安全、兼容性或数据一致性的缺陷；风格和可选
优化留待后续。使用从 `0` 开始的计数器，只有连续三轮无此类问题且未修改任何代码时
才能结束；一旦修改代码，计数器重置为 `0`，修复后重新通过基本集成验证，再开始三轮。

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
- 向量记忆/RAG：`SILICONFLOW_API_KEY`、`SILICONFLOW_URL`、`SILICONFLOW_MODEL`，
  并显式打开 `VECTOR_MEMORY_ENABLED=true` 或 `RAG_ENABLED=true`；
  `SILICONFLOW_DIMENSIONS` 仅在 provider 支持自定义维度且同时设置
  `SILICONFLOW_DIMENSIONS_ENABLED=true` 时发送。

在 zsh 中加载 `.env` 使用：

```bash
set -a && source .env && set +a
```

不要把 `export $(cat .env ... )` 作为默认操作；复杂值和 zsh 的解析会使这种写法不可靠。

### 一键开发环境与 Maven 命令

根目录 `dev.sh` 会读取 `.env`，默认以 `postgresql` profile 启动后端 `8080` 和前端
`4000`，等待两个 HTTP 服务健康后返回。若 `.env` 使用
`SPRING_DATASOURCE_URL`/`SPRING_DATASOURCE_DRIVER_CLASS_NAME` 加
`POSTGRES_USER`/`POSTGRES_PASSWORD`，脚本会将后两个变量映射为 Spring datasource
用户名/密码；不会打印密钥。

```bash
./dev.sh                 # 后端 + frontend
./dev.sh --backend-only  # 只启动后端
./dev.sh --frontend-only # 只启动 frontend
./dev.sh --stop          # 精确停止 8080/4000 的监听进程
```

可用 `BACKEND_PORT`、`FRONTEND_PORT` 和 `DEV_RUNTIME_DIR` 覆盖端口/临时日志目录。
开发服务日志写入系统临时目录，不写入仓库。

```bash
# 编译、打包，不访问外部 LLM API
mvn -DskipTests clean package

# 启动后端，默认监听 8080
mvn spring-boot:run -DskipTests

# 运行全部 Maven 测试
mvn test
```

默认 `mvn test` 排除 `live-llm` 和 `container` 两个 JUnit 标签，因此不访问真实 LLM、
PostgreSQL 或 Docker。真实 OpenAI-compatible provider 测试使用
`RUN_LIVE_LLM_TESTS=true` 显式启用，同时必须用
`-Dtest.excluded-groups=container` 放开 `live-llm` 标签；PostgreSQL profile 测试使用
`container` 标签显式启用。只设置环境变量而不覆盖 Maven 排除组会得到 `Tests run: 0`，
不能作为真实调用证据。

后端默认端口为 `8080`。应用运行后：

- 静态页面：`http://localhost:8080/`
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`
- H2 控制台：`http://localhost:8080/h2-console`，仅非 `postgresql` profile 时有意义

### 数据库与 profile

- `postgresql` profile：PostgreSQL JDBC Chat Memory + PgVectorStore，需要 PostgreSQL、`vector` 扩展及对应凭证。
- 直接运行 Maven 且不指定 profile：基础配置使用文件型 H2；如果没有打开向量能力开关，
  Skills Demo 不要求 Embedding。
- 任意非 `postgresql` profile：使用文件型 H2 和两个独立的 `SimpleVectorStore`，知识库
  默认文件为 `./data/vector-store.json`，语义记忆默认文件为
  `./data/chat-memory-vector-store.json`。例如：

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -DskipTests
```

`application-dev.yml` 只有在显式使用 `dev` profile 时才会加载。修改数据库配置时同时检查 `application.yml`、`application-postgresql.yml`、`VectorStoreConfig` 和 `VectorStorePostgresqlConfig`。

## Agent 架构

### 普通聊天链路

`POST /api/chat` 的 JSON 请求进入 `ChatController`，然后调用 `AgentService`：

1. 为本次请求创建独立的 Skill 会话和 ToolContext，并复制已验证的认证信息。
2. 通过 `SkillsAdvisor` 注入技能目录、本轮已加载 Skill 内容和 backend 工具规则。
3. 通过 `MessageChatMemoryAdvisor` 读取 JDBC 对话窗口。
4. 按开关选择性地注入独立的 `VectorStoreChatMemoryAdvisor` 和知识库
   `QuestionAnswerAdvisor`。
5. 通过 `ToolCallAdvisor` 驱动 Spring AI 工具回合；Advisor 顺序固定为：

   ```text
   SkillsAdvisor (+0)
     -> MessageChatMemoryAdvisor (+100)
     -> VectorStoreChatMemoryAdvisor (+150，可选)
     -> QuestionAnswerAdvisor (+200，可选)
     -> ToolCallAdvisor (+300)
   ```

   这些数值相对于 `Ordered.HIGHEST_PRECEDENCE`；数值越小越靠外层。记忆/RAG 在工具
   循环前准备上下文，`ToolCallAdvisor` 在链尾驱动回合；`SkillTools` 通过
   `ChatClient.Builder.defaultTools(skillTools)` 注册。可直接运行
   [`SkillsAdvisorTest`](src/test/java/com/example/demo/agent/SkillsAdvisorTest.java) 和
   [`AgentServiceTest`](src/test/java/com/example/demo/service/AgentServiceTest.java) 观察该
   契约。
6. 使用 `SkillTools` 执行只读 GET，或使用 `buildHttpRequest` 生成写操作确认元数据。
7. `POST /api/chat/text` 对待确认写操作返回后端生成的 `response` 和结构化
   `confirmation`；旧 `/api/chat` 与普通 SSE 使用后端生成的 `[CONFIRM_REQUIRED]`
   文本兼容协议。
8. 传统页面通过当前 Skill API index 二次校验确认元数据，只有用户点击确认后才使用
   最新浏览器 token 发送真实写请求。

普通链路的 `AgentService` 使用 `MessageWindowChatMemory(maxMessages=20)`。

普通链路注册的是完整 `SkillTools`：`loadSkill` 负责加载技能，`readSkillReference`
读取分层参考文件，`httpRequest` 在 Java 端只执行已登记的 GET，`buildHttpRequest`
只构建并登记请求级写操作确认元数据，不执行写 API。不要把这组工具与 AG-UI 路径的
`SkillCoreTools` 混为一谈。详细协议见
[系统架构](docs/ARCHITECTURE.md) 和 [REST/SSE API](docs/rest-api.md)。
普通工具还会按 API index 检查 API/参考文件所属 Skill 是否已在当前请求加载；只加载
其他 Skill、跳过 `loadSkill` 或缺少请求上下文都不能绕过门禁。对应正常/负向回路见
[`SkillToolsTest`](src/test/java/com/example/demo/agent/SkillToolsTest.java) 和
[`BackendApiIntegrationTest`](src/test/java/com/example/demo/BackendApiIntegrationTest.java)。

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
- 使用 `JsonArgToolCallback` 兼容当前 AG-UI 工具参数边界。

AG-UI Agent 的 `MessageWindowChatMemory` 当前只保留最近 4 条消息。不要把它和普通 `AgentService` 的 20 条窗口混为一谈。

### Skills 渐进式披露

`SkillRegistry` 启动时扫描 `classpath:skills/*/SKILL.md`，解析 YAML frontmatter 和 Markdown body，并建立 API 索引：

- Level 1：`SkillsAdvisor` 注入所有 Skill 的名称和描述。
- Level 2：`loadSkill` 返回某个 Skill 的完整 `SKILL.md` 内容及关联 Skill 提示。
- Level 3：分层 Skill 使用 `readSkillReference` 读取 `references/` 下的单个资源、操作或 schema 文档。

`RuntimeSkillCatalogService` 将同一 `SkillRegistry` 映射为只读观察 API：

- `GET /api/skills`：Level 1 目录，不返回正文。
- `GET /api/skills/{name}`：Level 2 正文、links 和该 Skill 的 API index 条目。
- `GET /api/skills/api-index`：中性的 method/path allowlist。
- `GET /api/agui/skills/api-index`：旧兼容别名，必须委托同一 catalog service。

这些 HTTP API 只用于观察和客户端 URL 校验，不提供 Level 3 reference 下载；
`readSkillReference` 仍是受限 reference 读取边界。嵌入式传统页面使用中性路径，
Next.js/CopilotKit hook 当前仍使用旧兼容别名。

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

提示词本身也有明确的教育契约：`SkillsAdvisor` 通过
[`PromptLoader`](src/main/java/com/example/demo/service/PromptLoader.java) 优先读取
`src/main/resources/prompts/skills-advisor/` 下的 classpath 模板；`PromptLoader` 中的
Java fallback 只用于资源不可用时，并通过
[`PromptLoaderTest`](src/test/java/com/example/demo/service/PromptLoaderTest.java) 与资源
版本保持一致。修改 SkillsAdvisor 的工具名、参数形状或 Skill 门禁规则时，必须同步检查
资源模板、fallback 和该测试；普通 backend 模板与 frontend/AG-UI 模板不能互换。具体的
问题背景、实现边界和验证记录见
[Prompt 资源与 fallback 契约规划](docs/drafts/prompt-fallback-contract-hardening-plan.md)。

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
| `GET /api/skills` | Level 1 Skill 目录 |
| `GET /api/skills/{name}` | Level 2 Skill 正文与 API 条目 |
| `GET /api/skills/api-index` | 中性 Skill API 索引，供传统页面和新客户端 URL 校验 |
| `GET /api/agui/skills/api-index` | 旧 Skill API 索引兼容别名 |
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
- 当前 token 是 Base64 编码的 `username:password` 两段字符串，不是 JWT，不提供签名、
  过期时间或可靠的完整性保护。
- `/api/auth/login`、传统页面和测试均使用同一 `username:password` token 契约。
- `validateToken()` 会校验用户名和密码；错误密码、篡改第二段或未知用户都会被拒绝。
- 前端把 token 放在 `localStorage`，CopilotKit BFF 和浏览器侧 `httpRequest` 会透传 `Authorization`。
- `AuthFilter` 将认证放入 `SecurityContextHolder`，并通过 FilterRegistrationBean 禁止
  Servlet 容器和 Security chain 重复执行。
- 普通 Agent 不依赖 `UserContextHolder` 或隐式线程继承，而是把已验证 token 显式复制到
  Spring AI `ToolContext`；AG-UI 旧链路仍有自己的异步上下文兼容逻辑。

不要在新代码中把这个 token 称为 JWT，也不要把它作为生产认证方案。修改认证时，重点检查
`AuthFilter`、`SecurityConfig`、`AuthController`、普通 Agent 的 `ToolContext` 和 AG-UI
链路的异步上下文边界。

当前写操作的用户确认主要由 CopilotKit 前端 `useHumanInTheLoop` 实现。`app.confirm-before-mutate` 已不是当前配置项；不要依据旧文档恢复该模式。

## 记忆、向量库与知识库

- JDBC Chat Memory 使用 `JdbcChatMemoryRepository`。
- 非 PostgreSQL profile 使用 H2 文件 `./data/chat-memory.mv.db`。
- `KnowledgeBaseInitializer` 默认加载 `classpath:knowledge-base/*.md`。
- 可通过 `KNOWLEDGE_BASE_PATHS` 传入逗号分隔的 classpath/file glob。
- `VectorStoreChatMemoryAdvisor` 仅在 `VECTOR_MEMORY_ENABLED=true` 时启用，并使用独立的
  `chatMemoryVectorStore`。
- `QuestionAnswerAdvisor` 仅在 `RAG_ENABLED=true` 时启用，并使用独立的
  `knowledgeVectorStore`；知识库初始化也只在该开关打开时执行。
- 非 PostgreSQL profile 使用两个独立的 `SimpleVectorStore`，应用关闭时分别持久化到
  `./data/vector-store.json` 和 `./data/chat-memory-vector-store.json`。
- PostgreSQL profile 使用两个独立的 `PgVectorStore`，表名默认 `vector_store` 和
  `chat_memory_vector_store`，维度默认 1024，索引默认 HNSW，距离默认余弦距离。
- `KnowledgeBaseInitializer` 使用规范化 source 生成稳定文档 ID，并写入
  `metadata.kind=knowledge`；重复初始化同一 source 会复用同一 ID。

向量功能依赖嵌入模型。修改 embedding URL 时注意 `SILICONFLOW_URL` 不应带 `/v1`，因为 `OpenAiEmbeddingModel` 会追加路径。

## 前端开发

当前前端实际端口以 `frontend/package.json` 为准：

```bash
cd frontend
npm ci --registry=https://registry.npmjs.org
npm run dev
```

开发服务器默认是 `http://localhost:4000`。`frontend/README.md` 和历史根 README 中仍有 `3000`/`3001` 的旧描述，不要据此改端口。

前端后端地址：

- `JAVA_BACKEND_URL`：Next.js BFF 在服务端访问 Java 后端。
- `NEXT_PUBLIC_JAVA_BACKEND_URL`：浏览器侧登录和 `httpRequest` 访问 Java 后端。
- 两者默认都是 `http://localhost:8080`。

认证状态同步有一个当前实现限制：`CopilotProvider` 会在初始化时读取一次 `localStorage.auth_token`，并监听 `storage`/`auth-changed` 事件；`AuthProvider` 当前登录和登出逻辑没有派发 `auth-changed`。因此同一个页面内登录后，CopilotKit BFF 的 Authorization headers 可能仍是旧值，直到刷新页面或触发其他同步；浏览器侧 `httpRequest` 每次直接读取 localStorage，可能仍然可以正常携带最新 Token。遇到 AG-UI 登录后仍提示未认证时，先刷新前端页面。

CopilotKit 使用 v2 `CopilotKitProvider` 和 `useSingleEndpoint`。不要把 v1 的 `useCopilotAction.renderAndWaitForResponse` 方案重新引入。

前端构建所需的支持文件由主仓库跟踪：

- `frontend/scripts/transform-v2-css.mjs`
- `frontend/patches/copilotkit-v2-v3.css`

`package.json` 的 `postinstall` 会从锁定的 CopilotKit 依赖生成 CSS 补丁；
`CHECK_ONLY=true node scripts/transform-v2-css.mjs` 可只读检查生成物是否过期。
`frontend/patches/stubs/` 仅保留历史 Mermaid stub，当前不属于构建必需文件。
`package-lock.json` 必须只使用 npm 官方 registry；Next 15.5.23 的 PostCSS/Sharp
安全覆盖、旧 AI SDK 的 Undici 6.28.0 定向覆盖和 Node 22.19+ 门槛由
`package.json` 与仓库检查共同约束。这些 overrides 是高危漏洞的兼容性边界，
不要在没有干净安装、production build 和审计证据时删除或扩大。
`next.config.js` 必须把 `outputFileTracingRoot` 固定在 `frontend/`，避免父目录中的
其他 lockfile 改变 Next.js 构建追踪边界。
前端仓库可复现性检查使用：

```bash
cd frontend
npm run test:repository
```

全新 clone 不应依赖其他工作区或 ignored 文件恢复前端构建链。

## 测试与验证

Java 单元/集成测试：

```bash
mvn clean compile test-compile
mvn -Dtest='*Skill*Test,*Api*Test' test
```

仓库中的专项 Shell 脚本才负责按各自策略启动或复用后端；`mvn test` 只运行 Java 测试，
默认排除 `live-llm` 和 `container`：

```bash
./test.sh                     # 已有健康服务则复用，否则启动；商品、聊天、认证和基础回归
./test-petstore.sh            # 清理 8080 后启动；分层 Swagger Petstore Skill
./test-vector-store-memory.sh # 清理 8080 后启动；向量记忆
./test-rag-knowledge-base.sh  # 清理 8080 后启动；知识库问答
./test-streaming.sh           # 已有健康服务则复用，否则启动；普通/多模态 SSE
```

要求已有后端运行的脚本：

```bash
./test-multimodal.sh           # 同步多模态；不会启动 Java 后端
./test-streaming-transcribe.sh # 纯转写 SSE；不会启动 Java 后端
./test-jwt-get.sh              # 同步/AG-UI GET 认证透传；不会启动 Java 后端
./test-agui-jwt-full.sh        # AG-UI 认证透传；不会启动 Java 后端
./test-sse-jwt.sh              # AG-UI SSE 认证透传；不会启动 Java 后端
```

这些专项脚本通常还要求 `.env`、有效的 LLM/Embedding/视觉/转写服务或 PostgreSQL。
`test-multimodal.sh`、`test-streaming.sh` 的多模态场景需要 `TEST_IMAGE_PATH`、
`TEST_AUDIO_PATH`；传统内嵌页面的真实浏览器验收使用：

```bash
./dev.sh --backend-only
cd frontend && npm run test:e2e:traditional
./dev.sh --stop
```

该 E2E 使用 Playwright headless Chromium，通过 DOM、可访问状态、`/api/auth/verify`
和 `/api/chat/text` 的网络状态，以及页面消费 JSON 后的商品结果完成断言，不使用截图。
`test-e2e-frontend.py` 仍用于 Next.js/CopilotKit AG-UI 页面。运行任何脚本前确认目标端口
没有不应被清理的旧进程，并确认 profile 与数据库依赖匹配。

本次 SKILL 改进的离线确定性测试不得访问真实 LLM、视觉、ASR、Embedding 或 PostgreSQL。
其他测试脚本和现有 `src/test` 可能访问这些外部服务。报告失败时先区分：

- 编译/应用上下文失败。
- 外部服务凭证或网络失败。
- 数据库/profile 未匹配。
- 真实 Agent/前端行为失败。

不要把一次外部 API 烟测失败直接归因于业务代码。

前端构建与 Mock 验收：

```bash
cd frontend
npm run test:repository
npx tsc --noEmit
npm run build
npm run test:skills
npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org
```

`test:skills` 只验证 Skill API index Mock、浏览器 URL allowlist 和工具 UI 的 DOM/网络
行为，不使用截图作为证据。需要真实 AG-UI、认证或模型时，再运行已有专项脚本，并把
环境依赖与实现结果分开记录。Playwright 默认使用其安装的 Chromium；下载源不可用且
机器已有 Chrome 时，可给同一测试命令增加 `PLAYWRIGHT_BROWSER_CHANNEL=chrome`。

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
- 涉及 API 路径时，同时检查 `SkillRegistry` API index 和前端
  `frontend/lib/api-index-validation.mjs`/`validateUrl`。
- `.agents/skills/` 下的 Agent Skill 包必须自包含、使用相对引用，不能包含源机器绝对路径或密钥。
- 不要提交 `.env`、`data/`、`target/`、`node_modules/`、Playwright 截图和调试产物。
- 提交前至少运行 `mvn -DskipTests clean package`，并检查相关专项测试是否具备所需外部依赖。
- 若改动只涉及文档，也要用 `git diff --check` 和 `git status --short` 验证。
- 不要回滚用户已有的未提交改动；本次任务只应修改明确涉及的文件。
