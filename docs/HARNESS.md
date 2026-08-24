# 验证手册

> **目的**: 根据改动范围选择最小的验证命令，并正确解释外部服务失败。
> **最后核对**: 2026-08-24

## 验证分层

| 层级 | 命令/入口 | 适用范围 | 外部依赖 |
|---|---|---|---|
| 开发环境 | `./dev.sh` | 从 `.env` 启动后端和前端并等待健康 | `.env`、Maven、Node、数据库/模型 |
| 文档格式 | `git diff --check` | 所有文档改动 | 无 |
| Git 范围 | `git status --short` | 提交前 | 无 |
| 后端硬门槛 | `mvn clean compile test-compile` | Java、配置、资源和 Skill | Maven 仓库 |
| Skills 确定性测试 | `mvn -Dtest='*Skill*Test,*Api*Test' test` | Skill、reference、API index 契约 | Maven 仓库，无 LLM |
| 后端教育闭环 | `mvn -Dtest='BackendApiIntegrationTest,ChatControllerTest' test` | API index mapping、Tool Calling 确认边界、购物车结算、普通文本 SSE | Maven 仓库；使用 Scripted ChatModel |
| Maven 测试 | `mvn test` | 默认确定性 Java 测试和上下文 | Maven 仓库；排除 live-llm/container |
| 前端类型检查 | `cd frontend && npx tsc --noEmit` | TypeScript/React | Node 依赖 |
| 前端仓库可复现性 | `cd frontend && npm run test:repository` | postinstall、CSS 补丁和入口引用 | Node 依赖 |
| 前端生产依赖审计 | `cd frontend && npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org` | critical/high 生产依赖门槛 | npm 官方 registry |
| 前端构建 | `cd frontend && npm run build` | Next.js、CopilotKit、CSS、TypeScript | Node 依赖和本地 patch |
| 前端 Skills Mock 验收 | `cd frontend && npm run test:skills` | API index、URL 校验和工具 DOM/网络行为 | Node 依赖、Playwright |
| 传统页面真实只读 E2E | `cd frontend && npm run test:e2e:traditional` | 内嵌页面登录、普通 Agent、DOM 商品结果 | 已启动后端、真实 LLM/Embedding、Playwright |
| 传统页面真实写操作 E2E | `cd frontend && npm run test:e2e:traditional:mutation` | Tool Calling、取消/确认、认证透传、结果解释和购物车清理 | 已启动后端、真实 LLM、PostgreSQL/商品 API、Playwright |
| 基础回归 | `./test.sh` | 商品、聊天、认证 | 后端、`.env`、LLM |
| PetStore | `./test-petstore.sh` | 分层 OpenAPI Skill | 后端、LLM |
| 记忆/RAG | `./test-vector-store-memory.sh`, `./test-rag-knowledge-base.sh` | 向量记忆和知识库 | Embedding、数据库/profile；脚本会启动后端 |
| 多模态 | `./test-multimodal.sh`, `./test-streaming-transcribe.sh` | 图片、音频、转写 | 视觉/转写服务和测试文件 |
| 流式 | `./test-streaming.sh` | 普通和多模态 SSE | 后端、LLM、测试文件；无服务时脚本会启动 |
| AG-UI | `./test-agui-jwt-full.sh`, `./test-sse-jwt.sh` | AG-UI、认证和 SSE | 已运行的后端、LLM、认证 |
| 浏览器 E2E | `python test-e2e-frontend.py` | Next.js + CopilotKit | 后端 8080、前端 4000、Playwright |

## 脚本启动策略

### 可以自行启动后端

- `test.sh`：如果 `/api/products` 已健康则复用，否则清理端口后启动；默认测试结束后保留服务。
- `test-petstore.sh`：清理 8080 后自行启动 Spring Boot。
- `test-vector-store-memory.sh`：清理 8080 后自行启动 Spring Boot，需要 Embedding 配置。
- `test-rag-knowledge-base.sh`：清理 8080 后自行启动 Spring Boot，需要 Embedding 配置。
- `test-streaming.sh`：已有健康服务时复用，否则自行启动；多模态场景还需要测试媒体文件。

### 必须先启动后端

以下脚本只发送请求，不负责启动 Java 服务；直接执行时若 8080 没有服务，会得到连接失败：

- `test-multimodal.sh`
- `test-streaming-transcribe.sh`
- `test-jwt-get.sh`
- `test-agui-jwt-full.sh`
- `test-sse-jwt.sh`

这些脚本依赖的后端必须已经使用匹配的 LLM、视觉、转写、数据库和认证配置启动。
传统页面 E2E 只需要后端；`test-e2e-frontend.py` 还需要另行启动 `frontend` 的 4000 端口。

`dev.sh` 读取根 `.env`，默认使用 `postgresql` profile，并将 `POSTGRES_USER`、
`POSTGRES_PASSWORD` 映射为 Spring datasource 凭证。没有 PostgreSQL 时，在 `.env` 中
设置 `SPRING_PROFILES_ACTIVE=local`，再运行 `./dev.sh`。

## Mock 优先与真实 LLM

实现任务先执行离线确定性测试和 Mock 验收，再决定是否需要真实 LLM 集成测试。Mock
测试用于快速证明 frontmatter、reference、API index、URL 校验、DOM 和网络契约；
真实 LLM 测试只补充模型工具选择、工具参数协议、SSE/AG-UI 和跨服务链路证据，不能
替代前面的质量门槛。

用户明确允许使用本地 `.env` 凭证后，真实验证应按以下规则执行：

1. 只选择能证明本次改动的最小场景，并控制模型调用次数和数据范围。
2. `dev.sh` 存在时优先使用它，并明确记录 profile、端口和 `.env` 中的数据库/模型
   配置；需要特殊隔离参数时再使用显式 Maven 命令。
3. 运行期间持续观察后端日志、SSE 事件和 HTTP 响应；看到启动/认证错误、外部服务
   401/403、工具重复循环或无响应时，不要傻等，立即停止并分类记录。
4. 非 `main` 工作区必须使用隔离端口、服务、测试数据库/数据目录和日志；main 工作区
   的 `.env` 只用于获授权的本地测试，任何密钥都不能打印或提交。

OpenAI-compatible provider 的独立真实测试：

```bash
set -a && source .env && set +a
RUN_LIVE_LLM_TESTS=true \
  mvn -Dtest.excluded-groups=container \
  -Dtest=OpenAiCompatibleApiLiveTest test
```

`RUN_LIVE_LLM_TESTS=true` 只满足 JUnit 条件，不能自行覆盖 Surefire 默认排除组；命令输出
必须明确显示 `Tests run: 1`，`Tests run: 0` 不算通过。

例如本次 Skill 改动的真实证据应优先覆盖：`/api/agui/skills/api-index` 返回索引、
AG-UI `loadSkill` 调用次数、必要的 `readSkillReference`、浏览器/后端工具回合，以及
最终 API 路径或结果。Embedding、RAG、多模态和全量回归只有在确实属于改动范围时才加入。

## 标准序列

### 文档或 Java 改动

```bash
git diff --check
mvn clean compile test-compile
git status --short
```

即使只做文档改动，也应运行 `git diff --check` 和本地链接检查；后端编译用于确认文档中的命令和资源路径仍对应当前构建基线。

### 前端改动

```bash
cd frontend
npm ci --registry=https://registry.npmjs.org
npm run test:repository
npx tsc --noEmit
npm run build
npm run test:skills
npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org
```

Playwright 默认启动其安装的 Chromium，首次运行可执行
`npx playwright install chromium`。如果下载源不可用且机器已有 Chrome，可在
`test:skills`、`test:e2e:traditional:mock` 或真实 E2E 命令前设置
`PLAYWRIGHT_BROWSER_CHANNEL=chrome`；这只切换浏览器发行通道，不改变断言。

真实传统页面 E2E（必须先通过以上 Mock/构建门槛，并得到真实 LLM 使用授权）：

```bash
./dev.sh --backend-only
(cd frontend && npm run test:e2e:traditional)
(cd frontend && npm run test:e2e:traditional:mutation)
./dev.sh --stop
```

这些测试以 DOM、可访问状态、认证/聊天网络响应、页面消费 JSON 后的商品结果和只读
购物车查询为证据，不使用截图；它们覆盖内嵌传统页面的 `/api/chat/text`，不替代
AG-UI/Next.js E2E。写操作脚本先取消一次确认，证明没有业务 POST；再确认一次，证明
页面使用最新 token 发送 POST、调用 `/api/explain-result`，并在结束时调用 checkout
清理当前用户购物车。

如果仓库可复现性检查失败，先确认
`scripts/transform-v2-css.mjs`、`patches/copilotkit-v2-v3.css` 和锁定的
`@copilotkit/react-core` 版本一致；不要从其他工作区复制文件，也不要把构建失败直接
归因于业务代码。

### 端点、Skill 或 Agent 工具改动

1. 先通过后端硬门槛和本次相关的 Skills 确定性测试。
2. 先通过前端 `tsc`、生产构建和核心 Mock Playwright；验收证据不包含截图。
3. 如本次改动需要真实模型，再按“Mock 优先与真实 LLM”规则执行最小真实链路并观察日志。
4. 启动后端并确认 `/api/agui/health`、`/api/agui/info`。
5. 访问 `/v3/api-docs` 或 Swagger 检查 Controller 映射。
6. 访问 `/api/agui/skills/api-index` 检查 Skill API index。
7. 运行与端点对应的专项脚本；如需要真实 LLM 或数据库，单独记录外部依赖。
8. 只有以上基本集成验证通过后，才进行三轮固定范围代码收敛检查。检查阶段不补测试，
   只修复影响正确性、成本安全、兼容性或数据一致性的缺陷；发生代码修改就重置三轮。

## Maven 测试的边界

默认 `mvn test` 通过 Surefire 排除 `live-llm` 和 `container` 标签，不访问真实 LLM、
PostgreSQL 或 Docker。显式启用真实/容器测试时，报告结果时区分：

- 编译或 Spring ApplicationContext 失败。
- 外部模型、Embedding、视觉、转写服务失败。
- PostgreSQL/pgvector 连接或 schema 失败。
- Agent 工具选择、SSE 或前端行为失败。

## 交付前硬门槛与收敛检查

实现任务必须遵循以下顺序：

```text
探索/规划
  -> 一次性设计并编写验收测试
  -> 实施代码
  -> 后端硬门槛 + 相关集成测试
  -> 前端 tsc + production build + Mock Playwright
  -> 三轮限定范围只读检查
  -> 更新文档/验证记录
```

前端不得以截图判断验收，只能以 DOM、可访问状态、网络、JSON、只读查询和自动化断言
为证据。Review 不是正确性证明，不能在 review 阶段进入“补测试/全量重跑/再 review”
的死循环。

## 测试记录

根目录 `TEST_REPORT.md` 是带日期的历史测试报告。它可以提供场景和输出样例，但不能替代当前命令；报告中的端口、认证命名和工具架构必须回到源码核对。
