# 系统架构

> **目的**: 说明当前系统的模块边界、请求路径和工具执行方式，帮助定位改动影响范围。
> **最后核对**: 2026-08-24

## 系统上下文

项目是一个 Spring Boot + Spring AI 示例，包含两套 Agent 入口：

1. **普通聊天链路**：Spring Boot Controller -> `AgentService` -> `ChatClient` -> Skills、记忆、RAG 和后端工具。
2. **AG-UI/CopilotKit 链路**：Next.js BFF -> `/api/agui` -> `SpringAIAgent` -> Spring AI ChatModel；浏览器侧执行 `httpRequest`。

两条链路共享业务 API、运行时 Skills、认证模型和部分配置，但工具注册和异步生命周期不同，不能混写。

## 模块地图

| 区域 | 路径 | 职责 |
|---|---|---|
| Agent 核心 | `src/main/java/com/example/demo/agent/` | Skill 注册、渐进式提示词、后端工具和参数适配 |
| 普通服务 | `src/main/java/com/example/demo/service/AgentService.java` | 同步/流式聊天编排、普通 ChatClient 调用 |
| Skill 观察目录 | `RuntimeSkillCatalogService`、`RuntimeSkillController` | 把同一 registry 映射为 Level 1/2/API index 只读 HTTP 视图 |
| 多模态服务 | `src/main/java/com/example/demo/service/MultimodalAgentService.java` | 图片、音频转文本/视觉输入后复用 Agent 能力 |
| HTTP 边界 | `src/main/java/com/example/demo/controller/` | 聊天、流式、认证、商品、AG-UI、转写和结果解释端点 |
| PetStore Mock | `src/main/java/com/example/demo/petstore/` | `/api/v3/pet/**`、`store/**`、`user/**` |
| 配置 | `src/main/java/com/example/demo/config/` | LLM、HTTP、Security、CORS、记忆和向量库 |
| AG-UI 本地实现 | `src/main/java/com/agui/` | 从 `ag-ui-4j` 子模块复制并本地维护的协议与 Spring AI 代码 |
| 运行时 Skills | `src/main/resources/skills/` | LLM 面向业务 API 的 Level 1/2/3 指令 |
| 知识库 | `src/main/resources/knowledge-base/` | 启动时加载并写入 VectorStore 的 Markdown |
| Next.js 前端 | `frontend/` | CopilotKit v2 UI、BFF、认证和浏览器 HTTP 工具 |
| 可移植 Agent Skills | `.agents/skills/` | 供 Agent 使用的仓库工作流，不参与运行时 Skill 注册 |

## 普通聊天请求流

```text
静态页面或 API 客户端
  -> POST /api/chat
  -> ChatController
  -> AgentService
  -> SkillsAdvisor + ChatMemory Advisors + QuestionAnswerAdvisor
  -> ChatClient / ChatModel
  -> SkillTools.loadSkill/readSkillReference/httpRequest/buildHttpRequest 或普通文本
  -> 商品/PetStore API
```

`AgentService` 每个请求创建独立的 `SkillLoadSession`，通过 Spring AI
`ToolContext` 传递 Skill 状态和已验证 bearer token，不依赖单例 Bean 的全局加载列表。
普通会话使用 `MessageWindowChatMemory` 保存最近窗口，并可按配置叠加 JDBC 记忆、语义记忆
和知识库检索。该链路注册完整 `SkillTools`：`httpRequest` 只执行已登记的 GET，
`buildHttpRequest` 只构建经校验的写操作请求元数据；二者都不应被描述成 AG-UI 浏览器工具。

普通 Agent 的写操作采用“模型提议、应用确认、浏览器执行”的教学边界：

```text
loadSkill
  -> buildHttpRequest(POST/PUT/PATCH/DELETE)
  -> MutationConfirmationSession（请求级 ToolContext）
  -> AgentChatResult.confirmation
  -> 传统页面读取 api-index 并再次校验
  -> 用户确认后浏览器发送真实 API 请求
  -> /api/explain-result 解释真实 HTTP 结果
```

`buildHttpRequest` 不发送写请求，也不把认证信息写入待确认元数据。一个 ChatClient
调用最多登记一个待确认写操作；非法 API、参数或第二个写操作不会污染或替换已有状态。
同步 `/api/chat/text` 返回结构化 `confirmation`，旧 `/api/chat` 和普通 SSE 仍提供
`[CONFIRM_REQUIRED]` 文本兼容协议。待确认状态存在时，后端生成“等待用户确认”的说明，
不采信模型可能提前输出的“已成功”文本。传统页面在展示和执行前都读取当前 Skill API
index、限制同源写 API，并在点击确认的瞬间读取最新 `localStorage` token。

Advisor 顺序固定为：Skills -> JDBC 短期记忆 -> 可选语义记忆 -> 可选 RAG ->
`ToolCallAdvisor`。这样检索上下文和会话消息在工具循环外构造，避免真实
OpenAI-compatible provider 返回 `content=null` 的中间 tool-call 消息触发 JDBC schema
错误。

知识库 RAG 当前只接入这条普通链路：`AgentService` 注册了 `QuestionAnswerAdvisor` 和 `VectorStoreChatMemoryAdvisor`。下方 AG-UI 的 `AgUiConfig` 只注册 `SkillsAdvisor` 与 `MessageChatMemoryAdvisor`，所以 Next.js/CopilotKit 链路当前不能自动获得同等的知识库检索能力。

## AG-UI/CopilotKit 请求流

```text
CopilotKit v2 UI
  -> frontend/app/api/copilotkit/route.ts
  -> Next.js BFF single endpoint
  -> POST /api/agui
  -> AgUiController
  -> AgUiService / AgentStreamer
  -> SpringAIAgent
  -> Spring AI ChatModel
```

### 工具边界

AG-UI 模式下，后端只注册：

- `loadSkill`
- `readSkillReference`

后端不向 AG-UI Agent 注册 `httpRequest` 或 `buildHttpRequest`。前端 `useHttpRequestTool.tsx` 注册唯一的 `httpRequest`：

- GET 自动执行。
- POST、PUT、PATCH、DELETE 显示确认界面后执行。
- 从浏览器 `localStorage` 读取 `auth_token` 并透传 `Authorization`。
- 执行前调用旧兼容路径 `/api/agui/skills/api-index` 校验相对 URL；未知、绝对或非法路径直接拒绝，
  不再自动改写模型提供的 URL。

`SpringAIAgent` 关闭 Spring AI 内部工具执行，手动执行已注册后端工具；遇到前端工具时结束当前 SSE run，等待前端 `respond()` 触发下一轮。该逻辑还包含：

- `JsonArgToolCallback`：兼容 AG-UI 前端工具 schema 的 JSON 参数边界。
- 工具名称去重，避免前后端同时注册同名工具。
- `maxToolCalls=5`：限制推理模型造成的工具循环。
- URL 校验：不接受未出现在 Skill API index 中的业务端点。

## Skills 渐进式披露

`SkillRegistry` 启动时扫描 `classpath:skills/*/SKILL.md`：

```text
Level 1: SkillsAdvisor 注入所有 Skill 的 name/description
    -> loadSkill(name)
Level 2: 返回完整 SKILL.md 和关联 links
    -> readSkillReference(name, relativePath)
Level 3: 读取 OpenAPI Skill references 下的资源/操作/schema 文档
```

同一数据还可以在不调用模型的情况下观察：

```text
GET /api/skills
  -> Level 1 name/description/version/links
GET /api/skills/{name}
  -> Level 2 Markdown body + API 条目
GET /api/skills/api-index
  -> method/path allowlist + Level 3 referencePath 指针
```

`RuntimeSkillCatalogService` 只映射 `SkillRegistry` 已解析的数据，不重新解析 Markdown。
HTTP 详情不会返回 reference 正文；Level 3 仍只能通过受限 `readSkillReference` 读取。
旧 `/api/agui/skills/api-index` 委托同一 service，仅用于现有客户端兼容。

当前运行时 Skill：

- `search-products`
- `get-product-detail`
- `add-to-cart`
- `view-cart`
- `checkout`
- `swagger-petstore-openapi-3-0`

`.agents/skills/project-docs/` 是面向 Agent 的文档工作流包，不会被 `SkillRegistry` 扫描。

## 存储和外部依赖

| 能力 | 当前实现 |
|---|---|
| 对话短期记忆 | Spring AI JDBC Chat Memory；窗口大小按 Agent 链路配置 |
| 非 PostgreSQL 向量库 | `SimpleVectorStore`，持久化到 `./data/vector-store.json` |
| PostgreSQL 向量库 | `PgVectorStore`，需要 `vector` 扩展 |
| Embedding | OpenAI-compatible SiliconFlow EmbeddingModel |
| 知识库 | `KnowledgeBaseInitializer` 加载 `classpath:knowledge-base/*.md` 或配置路径 |
| 视觉 | 独立视觉模型配置，通过多模态服务使用 |
| 转写 | 独立 OpenAI-compatible/转写模型，通过 `/api/transcribe/stream` 使用 |
| LLM | `openai`、`anthropic`、`minimax` 条件化 ChatModel |

根 `application.yml` 不设置 `spring.profiles.active`。直接 Maven 启动默认使用 H2；
`dev.sh` 在未覆盖时默认注入 `postgresql`。非 PostgreSQL profile 使用 H2 和
`SimpleVectorStore`，PostgreSQL profile 使用 JDBC Chat Memory 和两个命名的
`PgVectorStore`。

关于“知识库文档”和“运行时 Skills”应如何选择、扩展及与 Spring AI 2.0 原生能力对照，见 [知识库与运行时 Skills](knowledge-and-skills.md)。

## 认证边界

认证是 Demo 机制：

- 用户写死在 `AuthService`。
- 登录 API 返回 Base64 编码的 `username:password`，例如 `user1:password1`。
- `validateToken()` 会校验用户名和密码；错误密码、篡改第二段或未知用户会被拒绝。
- 该 Token 仍不提供签名、过期或可靠的完整性保护。
- 前端存储在 `localStorage`。
- 普通 Agent 从当前已验证的 `SecurityContext` 复制 token 到 `ToolContext`；
  AG-UI 链路仍保留自己的异步上下文兼容逻辑。

不要在文档或新代码中把该 Token 称为生产 JWT。修改认证时同时审阅普通聊天、AG-UI SSE、前端 BFF 和浏览器工具路径。
