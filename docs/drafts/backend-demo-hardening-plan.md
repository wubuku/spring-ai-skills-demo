# 后端与 Demo 全面加固规划

> **状态**: 已实施；硬门槛、三轮实现审查和最终文档复核已完成，待最终提交与推送
> **范围**: 普通 `AgentService`、Spring MVC 后端、知识库/记忆、确定性测试、PostgreSQL
> 容器集成测试、内嵌传统 Web UI；本轮不改造或验收 AG-UI/CopilotKit 请求链路
> **最后核对**: 2026-08-23

## 1. 目标与完成定义

本轮目标不是把示例项目改造成生产电商系统，而是让它成为一个更可信、可重复、可教学的
Spring AI Demo：

1. 普通聊天链路的 Skills 提示词、Java 工具 schema 和执行语义一致。
2. 不同请求的 Skill 加载状态、会话记忆和认证信息不会在普通链路中互相污染。
3. 模型只能直接执行已登记的只读 API；写操作必须返回前端确认元数据。
4. 知识库与向量会话记忆使用不同向量空间，知识库重复启动不产生重复文档。
5. 默认 `mvn test` 完全确定、无需真实 LLM、PostgreSQL 或 Docker；外部集成测试显式分层。
6. 后端 API、完整 Skills 工具回合、PostgreSQL profile 和传统页面都有自动化验收证据。
7. 普通 Agent 链路默认日志不输出完整系统提示、模型输入/输出或 token 片段；AG-UI
   诊断日志的历史 token 前缀问题属于本轮明确排除的后续安全债务。
8. Java、Spring Boot、Spring AI、SpringDoc 和 Docker 基线彼此一致，读者可以从文档理解
   哪部分是 Spring AI 能力、哪部分是项目自定义能力。

完成时必须满足：

- `mvn clean compile test-compile` 通过。
- 默认离线 `mvn test` 通过，且不会访问真实模型、Embedding 或数据库服务。
- PostgreSQL Testcontainers 集成测试在 Docker 网络可用时通过；若镜像拉取被外部
  TLS/代理阻断，必须保留失败日志并以本地 PostgreSQL profile 真实闭环作为补充证据。
- 前端 `tsc`、生产构建、Skills Mock Playwright、传统页面 Mock Playwright 通过。
- 使用 `.env` 启动 PostgreSQL profile 后，传统页面真实 LLM Playwright 最小闭环通过。
- Dockerfile 至少完成语法/构建验证；环境可用时构建镜像并检查健康命令。
- 实现后连续三轮限定范围审查无代码修改。
- 提交并推送，主仓库和两个子模块均干净。

## 2. 当前事实与主要风险

### 2.1 依赖和运行基线

实施前 `pom.xml` 使用 Spring Boot `3.4.2`、Spring AI `1.1.2`、SpringDoc `2.6.0`，Java
编译目标为 17。实施后的维护线为 Spring AI `1.1.8`，对应 Spring Boot 3.5.x；本轮
最终使用 Spring Boot `3.5.16`。

本轮默认升级到：

| 组件 | 目标 |
|---|---|
| Java 编译/运行最低版本 | 17 |
| Spring Boot | 3.5.16 |
| Spring AI | 1.1.8 |
| SpringDoc | 2.8.17 |

这是留在 Java 17 / Spring Boot 3.x / Spring AI 1.1.x 内的补丁与维护线升级，不进行
Spring Boot 4 / Spring AI 2.x 主版本迁移。若兼容性验证出现无法在本轮低风险解决的
AG-UI 编译问题，回退顺序是先保留 Spring Boot 3.5.16，再把 Spring AI 固定回 1.1.2；
不能通过开启 Bean 覆盖或跳过编译掩盖问题。

### 2.2 普通 Skills 链路契约错位

实施前 `SkillsAdvisor` 无条件加载 `mode-rules.template`。该模板描述的是 AG-UI 浏览器
`httpRequest(method, url, params, body)`，而普通 `AgentService` 注册的是后端
`SkillTools.httpRequest(method, url, pathParams, queryParams, headers, body)`。这使
普通 Demo 依赖模型自行纠错，不能作为稳定的 Spring AI 工具示例。

实施前 `SkillTools` 是单例 Bean，`loadedSkills` 是进程级可变列表。每个请求开头调用
`reset()` 不能保证并发安全：请求 B 可以清除请求 A 的状态，A/B 也可以读取彼此加载的
Skill。`SkillsAdvisor` 读取同一状态进一步放大污染。

### 2.3 工具副作用和认证上下文

实施前普通链路的 `httpRequest` 可以直接执行 POST/PUT/DELETE，绕过内嵌页面已有的确认 UI。
模型还可以提供任意请求头。认证 token 依赖 `SecurityContextHolder`、
`UserContextHolder` 和全局 Reactor scheduler hook，普通流式链路难以证明上下文与请求
严格绑定。

本轮不重做 AG-UI 的异步认证传递，但普通链路改用 Spring AI `ToolContext` 显式传入
每次调用的 Skill 会话和已验证 bearer token。`ChatController` 不再把任意
`Authorization` 值手工标记为已认证；只接受 `AuthFilter` 验证后的 SecurityContext。
`AuthFilter` 不再向 `UserContextHolder` 写入普通请求 token，项目也不再把
`SecurityContextHolder` 全局切换为 `MODE_INHERITABLETHREADLOCAL`。AG-UI 控制器现有的
显式上下文代码和 scheduler hook 本轮保留，但不再成为普通链路的隐式依赖。

实施前认证契约存在两种不一致载荷：`/api/auth/login` 返回 Base64
`username:displayName`，传统页面和 Next.js 页面自行生成 Base64 `username:password`；
`AuthService.validateToken()` 又只检查用户名，因此错误密码或篡改第二段仍可通过。传统页面
还只检查 `/api/auth/verify` 是否返回 HTTP 2xx，没有读取 `valid` 字段，当前无效 token
同样可能被 UI 当成登录成功。

本轮保留 Base64 `username:password` 的“Demo bearer token”格式以避免扩展到完整认证
系统，但统一 `/login`、`/verify`、过滤器、页面和测试契约：后端必须校验用户名和密码，
无效 verify 返回 401，前端同时检查 HTTP 状态与 JSON `valid`。文档不再称其为 JWT；
普通 Agent 和认证 Filter 日志不记录 token 前缀，AG-UI 的历史诊断日志例外并按非目标
风险记录。

### 2.4 会话、输入和业务 API

实施前多个入口默认使用固定 conversation ID `default`，匿名用户或不同用户可能共享记忆。
聊天 DTO 没有空值、长度或 conversation ID 格式约束。商品不存在时返回 500；购物车使用
`ConcurrentHashMap<String, ArrayList<Long>>`，外层并发容器不能保护内部列表和结算竞态。

本轮使用“认证用户名/anonymous + 客户端会话 ID”作为内部会话键；空会话 ID 生成随机
值。传统页面在 `localStorage` 保存随机会话 ID。输入使用 Bean Validation，API 错误使用
一致的 `ProblemDetail`/HTTP 状态。购物车改为不可变列表配合 `ConcurrentHashMap.compute`
和原子 `remove`。

### 2.5 RAG 和向量记忆

实施前 `QuestionAnswerAdvisor` 和 `VectorStoreChatMemoryAdvisor` 共用一个 VectorStore。
知识文档和聊天记忆会进入同一检索空间，既影响答案质量，也不能清楚展示两种 Spring AI
用途。`KnowledgeBaseInitializer` 每次为文档生成随机 UUID，持久化存储重复启动会不断
增加同一来源文档。

实施前两个 Advisor 和知识库初始化始终启用。即使读者只想运行 Skills Demo，也必须
配置可用 Embedding；只有 LLM key 时，应用可能启动但第一次聊天在检索阶段失败。加固后的
Demo 必须允许 Skills、RAG 和向量记忆独立演示，而不是强制绑定所有外部依赖。

本轮建立两个 Bean：

- `knowledgeVectorStore`：继续使用现有 `vector_store` 表或 `data/vector-store.json`，
  供知识库和 `QuestionAnswerAdvisor` 使用。
- `chatMemoryVectorStore`：使用 `chat_memory_vector_store` 表或独立 JSON 文件，供
  `VectorStoreChatMemoryAdvisor` 使用。

知识文档 ID 由规范化 source URI 稳定生成；PGVector 和 SimpleVectorStore 都按 ID 覆盖，
因此重复启动幂等。加载失败可通过 `knowledge-base.fail-fast` 控制，默认保留 Demo 可启动
特性，但日志必须给出明确失败摘要。

新增独立功能开关：

| 配置 | 默认值 | 作用 |
|---|---:|---|
| `app.ai.rag.enabled` / `RAG_ENABLED` | `false` | 注册 `QuestionAnswerAdvisor` 并加载知识库 |
| `app.ai.vector-memory.enabled` / `VECTOR_MEMORY_ENABLED` | `false` | 注册 `VectorStoreChatMemoryAdvisor` |

JDBC 窗口记忆和 Skills 默认继续启用，因此只配置 ChatModel 就能运行核心 Demo。启用任一
向量能力时，启动校验必须要求非空 Embedding API key；`.env.example` 和 RAG 测试脚本显式
打开相应开关。默认关闭是可逆的部署配置，不删除任何 RAG 代码或文档。

### 2.6 HTTP、日志、配置和容器

实施前自定义 OkHttp interceptor 会缓存所有响应、在传输层重试，并在重试时把请求强制改成
POST；同时 Spring AI 还有自己的 retry 配置。普通 Agent 默认还会打印完整系统提示、
prompt、completion 和 token 前缀。`allow-bean-definition-overriding=true` 掩盖配置冲突。

`SpringAiConfig` 还创建了一个按 `List<ChatModel>` 首项选择模型的通用 `ChatClient`
Bean，但业务服务实际注入的是 Spring AI 自动配置的 `ChatClient.Builder`。同一容器又有
provider model、primary model、视觉和提示生成 ChatClient，这个“取列表第一项”的 Bean
既无明确消费者，也会误导读者对 Provider 选择方式的理解。

本轮移除自定义传输层重试和响应体日志，使用有界超时的 JDK HTTP request factory；重试
由 Spring AI 模型层负责。Prompt/completion/system prompt 日志默认关闭，仅通过显式环境
变量打开。PgVector 自动配置始终排除，由项目的两个命名 Bean 明确负责；关闭 Bean 覆盖。
删除未使用的通用 `ChatClient` Bean，只保留明确的 primary `chatModel` 和 Spring AI
自动配置的 `ChatClient.Builder`；视觉和 prompt-generation ChatClient 继续使用限定名。

实施前 Dockerfile 声称依赖虚拟线程并使用 Java 21 preview，但 Maven 目标是 Java 17，且健康检查
访问未引入 Actuator 的 `/actuator/health`。本轮统一为 Java 17、移除 preview，健康检查
使用公开的 `/api/products`。Compose 删除过时确认变量和错误的 SiliconFlow `/v1` 默认值，
明确选择 `local` 或 `postgresql` profile。

### 2.7 测试现状

实施前 Java 测试只有 Skill 解析/reference 单测和两个默认执行的真实 DeepSeek 测试；没有
Controller/Security、Agent 工具回合、知识库幂等、向量隔离或 PostgreSQL profile 自动化
测试。传统页面只有真实 LLM E2E，缺少秒级 Mock 验收。

真实 API 测试将合并为 provider-neutral `OpenAiCompatibleApiLiveTest`，标记
`live-llm` 并要求 `RUN_LIVE_LLM_TESTS=true`。默认 Surefire 排除 `live-llm` 和
`container`。真实模型测试只在所有确定性门槛通过后运行。

## 3. 目标设计

### 3.1 模式化 SkillsAdvisor

新增 Advisor context 参数：

```text
skills.execution-mode = backend | frontend
```

普通 `AgentService` 显式传 `backend`，使用新的
`prompts/skills-advisor/backend-mode-rules.template`：

- 先 `loadSkill`，分层 Skill 再 `readSkillReference`。
- GET 使用后端 `httpRequest`。
- POST/PUT/PATCH/DELETE 使用 `buildHttpRequest`，模型把 JSON 放入
  `http-request` 代码块，交给传统页面确认。
- 参数名与 Java `@Tool` 方法完全一致，Map 直接传 JSON 对象。

未传参数时保留 `frontend`，继续使用现有 AG-UI template。本轮不修改 AG-UI 执行器。
Advisor 的完整系统提示只在显式 `app.ai.log-system-prompt=true` 时以 DEBUG 输出。

### 3.2 普通工具会话

`AgentService` 每次调用创建一个 `SkillLoadSession`，通过
`ChatClient.PromptSpec.toolContext(...)` 传入。`SkillTools.loadSkill(..., ToolContext)`
从中读取并记录已加载 Skill。同步和流式调用都持有自己的对象，不依赖 Bean 全局状态。

Advisor 顺序显式固定为 Skills、JDBC 短期记忆、可选向量记忆、可选 RAG、ToolCall。
检索和记忆位于工具循环外层，避免重复检索，并防止 OpenAI-compatible provider 的
`content=null` 中间 tool-call 消息被 JDBC schema 拒绝。

`SkillCoreTools` 的现有 AG-UI 状态与普通 `SkillTools` 解耦；因为本轮不验收 AG-UI，不把
其状态重构计入完成条件。`SkillsAdvisor` 在 backend 模式不读取 AG-UI loaded state。

AG-UI 的 `AgUiController` 仍保留历史诊断日志和异步认证兼容逻辑，本轮不以普通链路的
脱敏和上下文证据替代 AG-UI 验收；后续若重新纳入范围，应单独移除 token 前缀日志并为
SSE/run 生命周期建立隔离测试。

`SkillTools.httpRequest(..., ToolContext)`：

- 只允许 GET。
- URL 必须通过 API index allowlist 和路径参数二次校验。
- 只允许 `Accept`、`Content-Type`、`X-Request-Id` 等明确请求头；禁止调用方覆盖
  `Authorization`、`Host` 和 hop-by-hop headers。
- bearer token 只从 `ToolContext` 中取。
- 对参数数量、键和值长度和响应长度设上限。
- 4xx/5xx 返回状态码和有界响应体，不泄露堆栈或底层连接详情。

`buildHttpRequest` 只接受写方法并复用同一 URL/参数校验。

`AgentService` 在调用 ChatClient 前从当前已验证的 `Authentication` 复制用户名和
credentials 到普通 Java Map，然后放入 ToolContext。异步流持有该不可共享 Map，不依赖
servlet ThreadLocal、Reactor 全局 hook 或 inheritable security context。

### 3.3 会话和 API 边界

新增 `ConversationIdResolver`：

- 客户端 ID 只允许字母、数字、`.`、`_`、`-`、`:`，最长 128。
- 空值生成 UUID。
- 内部键为基于 `user:<username>:<id>` 或 `anonymous:<id>` 生成的稳定 UUID，
  长度固定为 36，兼容 Spring AI JDBC Chat Memory 的 schema。

聊天请求最大 8 KiB；解释结果 body 最大 32 KiB；商品查询价格必须非负且
`priceMin <= priceMax`。全局异常处理统一 400/404/500 JSON，500 不返回内部异常详情。

### 3.4 存储分离和知识库幂等

两个 VectorStore 的配置、文件和表名均显式命名。`AgentService` 通过 `@Qualifier`
构造两个 Advisor，并按功能开关决定是否注册。`KnowledgeBaseInitializer` 只在 RAG
开启时加载，只写 knowledge store，并添加：

```text
metadata.kind = knowledge
metadata.source = normalized resource URI
id = UUID.nameUUIDFromBytes(source URI)
```

source key 对 classpath JAR 使用 `!/` 后的类路径，对开发时 `target/classes` 资源使用
类路径相对部分，对外部 `file:` 资源使用规范化绝对路径，避免开发运行和打包运行产生
不同 ID。重复加载同一 source 更新原记录。测试使用内存 VectorStore/fake Embedding；
PostgreSQL Testcontainers 测试验证两个表都能初始化且 Bean 不歧义。

### 3.5 测试分层

#### 默认离线测试

1. `SkillRegistryTest`、`SkillReferenceReaderTest`：保留并适配新工具签名。
2. `SkillToolsTest`：ToolContext 状态隔离、GET allowlist、写操作拒绝、确认元数据、认证
   header、非法 header/参数/URL。
3. `AuthServiceTest`：正确和错误密码、篡改 token。
4. `ConversationIdResolverTest`：用户命名空间、匿名随机、非法输入。
5. `ProductServiceTest`：用户隔离、并发添加、原子结算和不存在商品。
6. `KnowledgeBaseInitializerTest`：稳定 ID、重复初始化 ID 一致、metadata、读取错误策略。
7. `BackendApiIntegrationTest`：真实 Spring Context + HTTP 端口 + scripted `ChatModel`，
   覆盖登录/认证、商品 API、聊天输入校验、`loadSkill -> httpRequest -> final answer`
   完整 Spring AI 工具回合，并断言 backend prompt 契约。测试 profile 关闭 RAG/向量
   记忆，确保该测试只替换 LLM 外部边界且不访问 Embedding。

Scripted `ChatModel` 只替换外部 LLM 边界；Spring MVC、Security、Advisor、ChatClient、
Spring AI 工具执行、`SkillTools` 和真实本地商品 API 均使用生产实现。

#### 容器集成测试

`PostgresqlProfileIntegrationTest` 使用 `pgvector/pgvector:pg17`，验证：

- PostgreSQL profile ApplicationContext 启动。
- JDBC Chat Memory schema 初始化。
- `vector_store` 与 `chat_memory_vector_store` 分别存在。
- 两个命名 VectorStore Bean 可解析。

Docker 不可用时默认测试不受影响；显式容器命令才运行。

#### 浏览器 E2E

新增 `traditional-ui-mock-e2e.mjs`，由本地 Node HTTP server 提供真实
`static/index.html`，Mock 登录和 `/api/chat/text`，断言 DOM、网络、会话 ID 和错误状态；
不使用截图。

保留 `traditional-ui-e2e.mjs` 作为真实 LLM 最小闭环，增强为同时观察 `/api/auth/verify`
和 `/api/chat/text` JSON，并确认页面没有错误消息。

#### 真实 LLM

顺序固定：

```text
默认 Maven 测试
-> PostgreSQL container
-> frontend tsc/build/Mock Playwright
-> dev.sh --backend-only
-> provider-level live test
-> traditional-ui-e2e
```

真实调用使用 `.env`，不打印 key；运行期间轮询后端日志，发现 401/403、工具循环、模型
错误或无响应立即停止并分类。

## 4. 实施文件

预计修改：

- 依赖/配置：`pom.xml`、`application*.yml`、`.env.example`
- Skills：`SkillsAdvisor`、`SkillTools`、`SkillCoreTools`、`PromptLoader`、新增
  `SkillLoadSession`、新增 backend prompt template
- 普通 Agent：`AgentService`、聊天 DTO/Controllers、新增 `ConversationIdResolver`
- API/认证：`AuthService`、`AuthFilter`、移除
  `SecurityContextInheritanceConfig`、`ProductService`、`ProductController`、新增全局
  异常处理
- RAG/向量：`AgentService` 功能开关、`KnowledgeBaseInitializer`、两个 VectorStore
  配置、`VectorStorePersistenceExecutor`
- 传输/日志：`SpringAiConfig`（包括移除歧义 ChatClient Bean）、`RestConfig`
- Demo/容器：`static/index.html`、`Dockerfile`、`docker-compose.yml`、
  `test-rag-knowledge-base.sh`、`test-vector-store-memory.sh`
- 测试：`src/test/**`、`src/test/resources/application-test.yml`、
  `frontend/tests/traditional-ui-mock-e2e.mjs`、`frontend/package.json`
- 文档：`README.md`、`AGENTS.md`、`docs/ARCHITECTURE.md`、
  `docs/DEVELOPMENT.md`、`docs/configuration.md`、`docs/rest-api.md`、
  `docs/OPERATIONS.md`、`docs/HARNESS.md`、`docs/troubleshooting.md`、
  `docs/knowledge-and-skills.md`、`docs/drafts/README.md`、`frontend/README.md`

不修改：

- `src/main/java/com/agui/**`
- `AgUiController`、`AgUiConfig`、Next.js CopilotKit 执行链路
- `ag-ui-4j/`、`spring-ai-agent-utils/` 子模块 gitlink
- 多模态模型、语音转写的业务实现（允许为会话 ID 兼容做最小转发修复）

如果升级 Spring AI 后 AG-UI 复制源码仅需编译兼容调整，允许做最小签名修复，但不改变其
行为，也不把 AG-UI 测试纳入本轮验收。

## 5. 实施顺序

1. 先新增/改写确定性验收测试和 test profile；生产测试应先因缺少目标实现而失败。
2. 升级维护线依赖并移除里程碑仓库、Bean 覆盖和传输层重试。
3. 实施 backend/frontend Advisor 模式和 ToolContext Skill 状态。
4. 收紧只读/写操作工具边界、统一 Demo token、会话 ID 和 API 错误。
5. 分离 VectorStore，增加 RAG/向量记忆开关，实施知识库稳定 ID。
6. 修复 Docker/Compose 和传统页面会话 ID，增加 Mock Playwright。
7. 更新稳定文档，明确 Spring AI 与项目自定义层。
8. 运行全部硬门槛和真实闭环。
9. 完成三轮收敛检查后提交推送。

## 6. 验收命令

```bash
# 后端默认确定性门槛
mvn clean compile test-compile
mvn test

# PostgreSQL/pgvector 容器集成
mvn -Dtest.excluded-groups=live-llm \
  -Dtest=PostgresqlProfileIntegrationTest test

# 前端与 Mock 浏览器
cd frontend
npx tsc --noEmit
npm run build
npm run test:skills
npm run test:e2e:traditional:mock

# Shell/容器静态检查
bash -n dev.sh
docker compose config
docker build -t spring-ai-skills-demo:verification .

# Mock 全部通过后，使用 .env 的真实调用
set -a && source .env && set +a
RUN_LIVE_LLM_TESTS=true \
  mvn -Dtest.excluded-groups=container \
  -Dtest=OpenAiCompatibleApiLiveTest test
./dev.sh --backend-only
(cd frontend && npm run test:e2e:traditional)
./dev.sh --stop

git diff --check
git status --short --branch
git submodule status
```

如 Docker build 因外部镜像仓库网络失败，仍需完成 Dockerfile/Compose 静态检查并明确
记录；不能把网络失败写成镜像已验证。

## 7. 三轮实现审查范围

硬门槛全部通过后执行固定、互不重叠的只读检查：

1. **正确性/API 兼容**：普通同步/流式调用、Advisor 模式、工具 schema、Controller
   状态码、旧端点和传统页面。
2. **安全/一致性/成本**：并发 Skill 状态、会话命名空间、token、URL/header allowlist、
   写操作确认、向量隔离、知识库幂等、日志和重试。
3. **测试/交付/教学价值**：默认测试是否离线、容器/live 分层、Demo 文档能否解释 Spring
   AI 与项目代码边界、Docker/启动命令、提交范围和子模块状态。

发现会影响正确性、安全、兼容、成本或数据一致性的问题时立即修复，重新运行硬门槛，
审查计数归零。措辞或可选风格不触发重置。

## 8. 回滚与继续实施

改动按以下独立边界可回滚：

- 依赖升级：恢复 `pom.xml` 版本，不需要回滚业务数据。
- 向量分离：知识库仍使用原 `vector_store`；删除新的
  `chat_memory_vector_store` 可回退语义记忆，不影响知识数据。
- 稳定知识 ID：只会把同 source 写为同 ID；如需恢复随机 ID，只改加载器，但不推荐。
- ToolContext：恢复全局列表会重新引入并发污染；只能作为短期诊断回退。
- 写操作确认：恢复后端直接写会改变安全语义，不作为正常回滚方案。

任务中断后，从本文件“实施顺序”第一个未完成项继续，并先运行 `git status --short` 确认
已有修改；不要重新发明第二套计划。

## 9. 实施记录与剩余收敛

### 已实施

- Spring Boot `3.5.16`、Spring AI `1.1.8`、SpringDoc `2.8.17` 维护线升级。
- 普通 Agent 改用 backend mode 的 Skills prompt、`ToolContext` 会话、显式
  `ToolCallAdvisor` 顺序和只读 GET/写操作元数据边界。
- 修复 conversation ID 长度/用户命名空间、多模态 resolved ID、认证 Filter 重复注册、
 真实 provider 的空 assistant content 与 JDBC Memory 顺序问题。
- 分离知识库和语义聊天记忆 VectorStore，默认关闭两类 Embedding 能力；知识库 source
  使用稳定 ID 和 `kind=knowledge` 元数据。
- 增加后端 API/Agent/Skill/认证/知识库/会话/多模态测试，显式分组 live provider 和
  PostgreSQL Testcontainers。
- 增加 `dev.sh`、传统页面 Mock Playwright 和真实传统页面 Playwright 测试；Dockerfile
  和 Compose 与 Java 17/profile 事实对齐。
- 移除仍可绕过认证并指定任意 `userId` 的旧购物车写接口；购物车 HTTP 表面现在只保留
  基于已认证用户名的接口。
- 为 `readSkillReference` 增加相对路径长度上限，并将底层资源异常脱敏为通用错误，避免
  向模型暴露主机绝对路径。

### 已获得的验证证据

- `mvn clean compile test-compile`、默认确定性 Maven 测试和本次相关后端集成测试通过；
  当前默认 Maven 测试为 28 个测试全部通过。
- 前端 `tsc`、production build、Skills Mock Playwright 和传统页面 Mock Playwright 通过。
- OpenAI-compatible provider 的 `/v1/models`、基础聊天和 tool-calling 真实测试通过。
- 使用 PostgreSQL profile、真实 `.env` 模型和内嵌传统页面的 Playwright 闭环通过；页面
  通过 DOM、HTTP 状态/JSON 和商品结果断言，不使用截图。
- 本地 PostgreSQL 只读检查确认最新会话有 `USER/ASSISTANT` 两条消息，空 content 为零。

### 最终状态

1. 代码修改后的后端硬门槛和默认测试已重新执行并通过。
2. 实现代码已按正确性/API、 安全/一致性/成本、测试/交付/Demo 三个固定范围完成三轮
   收敛审查；审查期间发现的问题均已修复并重新通过硬门槛。
3. 规划文档已完成连续三轮无修改复核；最终执行密钥、构建产物、子模块和 Git 提交范围
   检查后提交并推送。

### 外部阻塞记录

Testcontainers 曾因本机 Docker Hub TLS 代理证书错误而无法拉取
`pgvector/pgvector:pg17`，错误表现为证书对 `simtraprod.idbs-cloud.com` 而非
`registry-1.docker.io`。再次尝试前不应把该容器测试写成已通过；如果环境仍未修复，应明确
报告为外部 Docker 网络阻塞，而不是实现失败。
