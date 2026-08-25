# 功能与代码地图

> **目的**: 从一个功能、用户问题或修改意图，直接定位入口、主要实现、配置/资源、
> 自动化证据和深入文档。
> **文档状态**: 稳定横向索引；不替代源码、配置、Swagger/OpenAPI 或运行时 Skill。
> **最后核对**: 2026-08-25

## 如何使用

- 想理解完整调用链，先从“入口与主要实现”进入 owner 代码，再读“证据与深入阅读”。
- 想修改行为，先确认该行列出的配置/资源和测试是否也属于同一契约。
- 本页只维护稳定入口，不复制参数表、配置默认值或协议正文；详细事实留在 owner 文件。

最常见的三个问题：

| 我想做什么 | 直接入口 |
|---|---|
| 通过指定公司保修、服务条款等文档给用户提供知识 | [通过知识库提供知识](knowledge-and-skills.md#路径一通过知识库提供知识) |
| 通过指定运行时 Skills 给用户提供查询或写操作服务 | [通过运行时 Skills 提供服务](knowledge-and-skills.md#路径二通过运行时-skills-提供服务) |
| 通过仓库内 Agent Skill 规范开发者或 Agent 的工作流 | [project-docs Agent Skill](../.agents/skills/project-docs/SKILL.md) |

## 业务与 Agent

| 功能或问题 | 入口与主要实现 | 配置或资源 | 证据与深入阅读 |
|---|---|---|---|
| 商品搜索、详情、购物车和结算 | `GET/POST /api/products/**`；[ProductController](../src/main/java/com/example/demo/controller/ProductController.java)、[ProductService](../src/main/java/com/example/demo/service/ProductService.java) | 商品运行时 Skills：[skills/](../src/main/resources/skills/) | [ProductServiceTest](../src/test/java/com/example/demo/service/ProductServiceTest.java)、[BackendApiIntegrationTest](../src/test/java/com/example/demo/BackendApiIntegrationTest.java)、[REST API](rest-api.md) |
| 普通 Agent 与 Spring AI Tool Calling | `POST /api/chat`、`POST /api/chat/text`；[ChatController](../src/main/java/com/example/demo/controller/ChatController.java)、[MultimodalChatController](../src/main/java/com/example/demo/controller/MultimodalChatController.java)、[AgentService](../src/main/java/com/example/demo/service/AgentService.java) | [SkillsAdvisor](../src/main/java/com/example/demo/agent/SkillsAdvisor.java)、[SkillTools](../src/main/java/com/example/demo/agent/SkillTools.java)、[提示词资源](../src/main/resources/prompts/) | [AgentServiceTest](../src/test/java/com/example/demo/service/AgentServiceTest.java)、[SkillsAdvisorTest](../src/test/java/com/example/demo/agent/SkillsAdvisorTest.java)、[BackendApiIntegrationTest](../src/test/java/com/example/demo/BackendApiIntegrationTest.java)、[学习主线](learning-path.md) |
| 运行时 Skills 与业务工具 | `GET /api/skills`、`GET /api/skills/{name}`、`GET /api/skills/api-index`；[RuntimeSkillController](../src/main/java/com/example/demo/controller/RuntimeSkillController.java)、[RuntimeSkillCatalogService](../src/main/java/com/example/demo/service/RuntimeSkillCatalogService.java) | [SkillRegistry](../src/main/java/com/example/demo/agent/SkillRegistry.java)、[SkillLoadSession](../src/main/java/com/example/demo/agent/SkillLoadSession.java)、[运行时 Skill 资源](../src/main/resources/skills/) | [SkillRegistryTest](../src/test/java/com/example/demo/agent/SkillRegistryTest.java)、[SkillToolsTest](../src/test/java/com/example/demo/agent/SkillToolsTest.java)、[知识库与运行时 Skills](knowledge-and-skills.md#路径二通过运行时-skills-提供服务) |
| filesystem、classpath 或 JAR 中的可复用 Skill 包 | 应用启动时由 [SkillResourceCatalog](../src/main/java/com/example/demo/agent/SkillResourceCatalog.java) 发现并固定同源 resource scope | `SKILL_LOCATIONS` / `app.skills.locations`；[SkillResourceProperties](../src/main/java/com/example/demo/config/SkillResourceProperties.java) | [SkillResourceCatalogTest](../src/test/java/com/example/demo/agent/SkillResourceCatalogTest.java)、[SkillResourcePropertiesTest](../src/test/java/com/example/demo/config/SkillResourcePropertiesTest.java)、[可执行 JAR smoke](../test-executable-jar.sh)、[复用指南](knowledge-and-skills.md#可复用-skill-资源包) |
| PetStore Mock 与 Level 3 OpenAPI Skill | `/api/v3/pet/**`、`/api/v3/store/**`、`/api/v3/user/**`；[petstore/](../src/main/java/com/example/demo/petstore/) | [petstore.yaml](../src/main/resources/petstore.yaml)、[分层 Skill](../src/main/resources/skills/swagger-petstore-openapi-3-0/) | 确定性：[SkillReferenceReaderTest](../src/test/java/com/example/demo/agent/SkillReferenceReaderTest.java)、[SkillRegistryTest](../src/test/java/com/example/demo/agent/SkillRegistryTest.java)；外部 LLM：[PetStore 专项脚本](../test-petstore.sh) |
| 普通 Agent 写操作确认 | 模型调用 `buildHttpRequest`，后端生成结构化确认；确认后浏览器才调用业务 POST；[SkillTools](../src/main/java/com/example/demo/agent/SkillTools.java)、[MutationConfirmationSession](../src/main/java/com/example/demo/agent/MutationConfirmationSession.java) | 传统页面 [index.html](../src/main/resources/static/index.html)、运行时 Skill API index | [BackendApiIntegrationTest](../src/test/java/com/example/demo/BackendApiIntegrationTest.java)、[SkillToolsTest](../src/test/java/com/example/demo/agent/SkillToolsTest.java)、[传统页面 Mock E2E](../frontend/tests/traditional-ui-mock-e2e.mjs)、[架构说明](ARCHITECTURE.md) |
| 真实 API 结果的自然语言解释 | `POST /api/explain-result`；[ExplainResultController](../src/main/java/com/example/demo/controller/ExplainResultController.java)、[ExplainResultService](../src/main/java/com/example/demo/service/ExplainResultService.java) | [解释提示词](../src/main/resources/prompts/explain-result/api-explanation-prompt.template)、Skill API index | [ExplainResultServiceTest](../src/test/java/com/example/demo/service/ExplainResultServiceTest.java)、[BackendApiIntegrationTest](../src/test/java/com/example/demo/BackendApiIntegrationTest.java)、[REST API](rest-api.md#结果解释) |

## 知识、记忆与模型

| 功能或问题 | 入口与主要实现 | 配置或资源 | 证据与深入阅读 |
|---|---|---|---|
| 知识库 RAG | 普通 Agent 通过 `QuestionAnswerAdvisor` 检索；[KnowledgeBaseInitializer](../src/main/java/com/example/demo/knowledge/KnowledgeBaseInitializer.java)、[AgentService](../src/main/java/com/example/demo/service/AgentService.java) | `KNOWLEDGE_BASE_PATHS`、`RAG_ENABLED`、[knowledge-base/](../src/main/resources/knowledge-base/)、[向量库配置](../src/main/java/com/example/demo/config/VectorStoreConfig.java) | 确定性：[KnowledgeBaseInitializerTest](../src/test/java/com/example/demo/knowledge/KnowledgeBaseInitializerTest.java)、[AgentServiceTest](../src/test/java/com/example/demo/service/AgentServiceTest.java)；外部 Embedding/LLM：[RAG 专项脚本](../test-rag-knowledge-base.sh)；[知识库指南](knowledge-and-skills.md#路径一通过知识库提供知识) |
| JDBC 短期记忆与可选向量记忆 | [AgentService](../src/main/java/com/example/demo/service/AgentService.java)、[ConversationIdResolver](../src/main/java/com/example/demo/service/ConversationIdResolver.java)、[ConversationHistoryService](../src/main/java/com/example/demo/service/ConversationHistoryService.java) | `VECTOR_MEMORY_ENABLED`、datasource、[VectorStoreConfig](../src/main/java/com/example/demo/config/VectorStoreConfig.java)、[VectorStorePostgresqlConfig](../src/main/java/com/example/demo/config/VectorStorePostgresqlConfig.java) | 确定性：[AgentServiceTest](../src/test/java/com/example/demo/service/AgentServiceTest.java)、[ConversationIdResolverTest](../src/test/java/com/example/demo/service/ConversationIdResolverTest.java)；container：[PostgresqlProfileIntegrationTest](../src/test/java/com/example/demo/PostgresqlProfileIntegrationTest.java)；[验证手册](HARNESS.md) |
| ChatModel Provider 选择 | [SpringAiConfig](../src/main/java/com/example/demo/config/SpringAiConfig.java) | [application.yml](../src/main/resources/application.yml)、[.env.example](../.env.example) | 真实 `live-llm` 基础聊天与 Tool Calling：[OpenAiCompatibleApiLiveTest](../src/test/java/com/example/demo/OpenAiCompatibleApiLiveTest.java)；[配置参考](configuration.md)、[模型抽象说明](spring-ai-model-abstraction.md) |
| Prompt 外部化与资源缺失 fallback | [PromptLoader](../src/main/java/com/example/demo/service/PromptLoader.java)、[SkillsAdvisor](../src/main/java/com/example/demo/agent/SkillsAdvisor.java) | [prompts/](../src/main/resources/prompts/) | [PromptLoaderTest](../src/test/java/com/example/demo/service/PromptLoaderTest.java)、[SkillsAdvisorTest](../src/test/java/com/example/demo/agent/SkillsAdvisorTest.java)、[学习主线](learning-path.md#prompt工具-schema-与后端门禁必须一起看) |

## UI 与集成

| 功能或问题 | 入口与主要实现 | 配置或资源 | 证据与深入阅读 |
|---|---|---|---|
| Demo 认证和请求身份 | `POST /api/auth/login`、`GET /api/auth/verify`；[auth/](../src/main/java/com/example/demo/auth/)、[SecurityConfig](../src/main/java/com/example/demo/config/SecurityConfig.java) | 硬编码 Demo 用户；Base64 token，不是生产 JWT | [AuthServiceTest](../src/test/java/com/example/demo/auth/AuthServiceTest.java)、[BackendApiIntegrationTest](../src/test/java/com/example/demo/BackendApiIntegrationTest.java)、[REST API](rest-api.md#认证) |
| 嵌入式传统 Web UI | `GET /`；[HomeController](../src/main/java/com/example/demo/controller/HomeController.java)、[index.html](../src/main/resources/static/index.html) | 后端 `8080`；浏览器使用 `/api/chat/text`、中性 Skill API index 和结果解释端点 | 确定性 Mock：[传统页面 Mock E2E](../frontend/tests/traditional-ui-mock-e2e.mjs)；真实 LLM：[查询 E2E](../frontend/tests/traditional-ui-e2e.mjs)、[写操作 E2E](../frontend/tests/traditional-ui-mutation-e2e.mjs)；[验证手册](HARNESS.md) |
| SSE、多模态和流式转写 | `/api/chat/stream`、`/api/chat/multimodal/stream`、`/api/transcribe/stream`；[ChatController](../src/main/java/com/example/demo/controller/ChatController.java)、[MultimodalChatController](../src/main/java/com/example/demo/controller/MultimodalChatController.java)、[StreamingTranscriptionController](../src/main/java/com/example/demo/controller/StreamingTranscriptionController.java) | [MultimodalAgentService](../src/main/java/com/example/demo/service/MultimodalAgentService.java)、[OpenAiStreamingTranscriptionService](../src/main/java/com/example/demo/service/OpenAiStreamingTranscriptionService.java)、视觉/转写配置和 prompt | 确定性局部契约：[ChatControllerTest](../src/test/java/com/example/demo/controller/ChatControllerTest.java)、[MultimodalAgentServiceTest](../src/test/java/com/example/demo/service/MultimodalAgentServiceTest.java)；外部视觉/转写/LLM：[流式脚本](../test-streaming.sh)、[多模态脚本](../test-multimodal.sh) |
| AG-UI/CopilotKit 链路 | `POST /api/agui`；[AgUiController](../src/main/java/com/example/demo/controller/AgUiController.java)、[AgUiConfig](../src/main/java/com/example/demo/config/AgUiConfig.java)、[SpringAIAgent](../src/main/java/com/agui/spring/ai/SpringAIAgent.java) | [CopilotKit BFF](../frontend/app/api/copilotkit/route.ts)、[浏览器 httpRequest 工具](../frontend/hooks/useHttpRequestTool.tsx)、`ag-ui-4j/` 子模块 | 外部 LLM/运行中服务：[AG-UI 专项脚本](../test-agui-jwt-full.sh)；[前端指南](../frontend/README.md)、[架构说明](ARCHITECTURE.md#ag-uicopilotkit-请求流) |

## 开发与工作流

| 功能或问题 | 入口与主要实现 | 配置或资源 | 证据与深入阅读 |
|---|---|---|---|
| 一键启动和本地开发 | [dev.sh](../dev.sh)、[pom.xml](../pom.xml)、[frontend/package.json](../frontend/package.json) | `.env` 只在本地使用；默认后端 `8080`、前端 `4000` | [开发指南](DEVELOPMENT.md)、[配置参考](configuration.md)、[故障排查](troubleshooting.md) |
| 构建、确定性测试、真实 LLM 和浏览器验收 | [后端测试](../src/test/)、[前端测试](../frontend/tests/)、根目录专项脚本 | 默认排除 `live-llm` 和 `container` 标签；真实调用显式启用 | [验证手册](HARNESS.md)、[TEST_REPORT](../TEST_REPORT.md) |
| 仓库内 Agent Skill | Agent 从 [.agents/skills/](../.agents/skills/) 读取可移植工作流；本项目文档工作流是 [project-docs](../.agents/skills/project-docs/SKILL.md) | 包内 `SKILL.md`、相对 `references/`；不参与 Spring Boot 运行时 Skill 扫描 | [AGENTS.md](../AGENTS.md)、[project-docs checklist](../.agents/skills/project-docs/references/checklist.md) |

## 维护规则

1. 新增或删除主要功能、入口端点、owner 模块或验收测试时，更新对应行。
2. 只变更实现细节且入口、owner 和验证方式不变时，不必更新本页。
3. API 参数和响应细节继续由 Controller、Swagger/OpenAPI、运行时 Skill 和
   [REST API](rest-api.md) 维护。
4. 配置默认值继续由 `application*.yml` 和 [配置参考](configuration.md) 维护。
5. 草稿只用于解释决策历史；本页不得把未实施草稿描述为当前功能。
