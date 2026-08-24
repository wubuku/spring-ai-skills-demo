# 普通 Agent Advisor 与 Tool Calling 契约加固规划

> **状态**: 已实施并验证
> **范围**: 普通 `AgentService` / `SkillsAdvisor` / Spring AI Tool Calling；不覆盖 AG-UI/CopilotKit
> **最后核对**: 2026-08-24
> **前置规划**: [后端与 Demo 教育性后续加固规划](backend-demo-hardening-follow-up-plan.md)
> **长期规则**: 本规划遵循根目录 [AGENTS.md 的规划、验收和三轮收敛原则](../../AGENTS.md)

## 1. 目标与完成定义

本轮继续加固当前项目的普通后端 Agent 和 Spring AI Demo 的教育价值。目标不是引入新的
Agent 抽象，而是让读者可以通过源码和确定性测试直接回答以下问题：

1. `SkillsAdvisor` 如何把 Level 1 Skill 目录和模式规则注入到 Spring AI `Prompt`？
2. 为什么普通 Agent 使用 backend 模式，而 AG-UI/浏览器工具使用另一套 frontend 模式？
3. Spring AI `ChatClient` 的 Advisor 为什么按“Skills -> 记忆 -> 可选向量记忆 ->
   可选 RAG -> Tool Calling”排列？
4. `SkillTools` 为什么作为 `defaultTools` 注册，`ToolCallAdvisor` 又为什么位于链尾？
5. 普通 Agent 每次请求如何创建独立的 `ToolContext`，避免 Skill 状态、确认状态和认证
   token 在请求之间共享？

完成定义：

- 新增的验收测试能够直接观察 `SkillsAdvisor.before(...)` 生成的系统提示，并区分
  backend、frontend 和未知模式的行为。
- `AgentService` 测试能够锁定可选 Advisor 的启用条件、绝对顺序和 `SkillTools` 工具注册。
- 相关测试使用 Spring AI 1.1.8 当前 API，不依赖真实 LLM、数据库、Docker 或浏览器。
- 测试失败时能明确指出是 Prompt 契约、Advisor 顺序、工具注册还是外部链路问题。
- 稳定文档提供从学习主线到源码、测试和验证命令的近距离入口。
- 不改变现有业务端点、Skill Markdown/frontmatter、确认协议、认证格式或 AG-UI/CopilotKit
  运行时行为。

## 2. 当前事实

### 2.1 普通 Agent 的生产链路

普通文本请求经过：

```text
POST /api/chat/text
  -> MultimodalChatController
  -> AgentService.chatResult(...)
  -> ChatClient.prompt()
  -> SkillsAdvisor
  -> MessageChatMemoryAdvisor
  -> 可选 VectorStoreChatMemoryAdvisor
  -> 可选 QuestionAnswerAdvisor
  -> ToolCallAdvisor
  -> SkillTools
  -> 商品/PetStore API
```

生产代码位置：

- 编排：[AgentService](../../src/main/java/com/example/demo/service/AgentService.java)
- 提示词 Advisor：[SkillsAdvisor](../../src/main/java/com/example/demo/agent/SkillsAdvisor.java)
- 普通后端工具：[SkillTools](../../src/main/java/com/example/demo/agent/SkillTools.java)
- Skill 注册和 API index：[SkillRegistry](../../src/main/java/com/example/demo/agent/SkillRegistry.java)
- 请求级 Skill 状态：[SkillLoadSession](../../src/main/java/com/example/demo/agent/SkillLoadSession.java)
- 请求级写操作确认：[MutationConfirmationSession](../../src/main/java/com/example/demo/agent/MutationConfirmationSession.java)

`AgentService` 在每次同步或流式调用中创建新的 `HashMap` ToolContext，并放入：

- `SkillLoadSession`
- `MutationConfirmationSession`
- 已认证请求的 bearer token 和用户名

因此不能把 `SkillTools` 的状态改回单例字段，也不能用 `SkillCoreTools` 的 AG-UI
singleton 状态替代普通链路的请求级状态。

### 2.2 SkillsAdvisor 的模式选择

`SkillsAdvisor` 的 `before(...)` 做三件事：

1. 从 `SkillRegistry.all()` 读取稳定排序的 Skill name/description，生成 Level 1 目录。
2. 根据 `request.context().get(SkillsAdvisor.EXECUTION_MODE)` 选择规则模板：
   - 精确值 `backend`：`backend-mode-rules.template`
   - 其他值或缺省：`mode-rules.template`
3. 使用 `PromptLoader` 读取
   `system-prompt.template`，填充 `SKILL_LIST`、`API_BASE_URL`、`HTTP_TOOL_NAME`、
   `LOADED_CONTEXT` 和 `MODE_RULES`，再通过 `augmentSystemMessage` 返回新请求。

当前 backend 模式的关键规则是：

- GET 只能使用 `httpRequest`；
- POST/PUT/PATCH/DELETE 必须使用 `buildHttpRequest`；
- 后端工具参数使用结构化 JSON 对象；
- 写操作只生成确认元数据，不得声称已经成功。

frontend 模式是 AG-UI/浏览器工具提示，描述字符串化参数和浏览器确认；普通
`AgentService` 必须显式传入 backend 模式。`SkillsAdvisor` 在 frontend 模式下还会从
`SkillCoreTools` 注入“本轮已加载 Skill”正文；backend 模式依赖 Spring AI 的
`ToolResponseMessage` 继续承载 `loadSkill` 返回值，不重复注入 `SkillCoreTools` 状态。

### 2.3 当前测试缺口

已有测试已经覆盖：

- `SkillRegistry` frontmatter、links、API index 和路径匹配；
- `SkillTools` 的请求级 Skill 门禁、GET allowlist、写操作确认、reference 读取；
- `BackendApiIntegrationTest` 的 Scripted `ChatModel` 工具回合、API index 与 MVC mapping、
  商品和购物车闭环；
- `PromptLoaderTest` 的 classpath 模板与 Java fallback 契约；
- `AgentServiceTest` 的 Advisor 类型和“记忆/RAG 在 ToolCallAdvisor 之前”这一相对关系。

但以下教育性契约仍没有直接锁定：

| 缺口 | 影响 |
|---|---|
| 没有独立 `SkillsAdvisor` 测试观察真实系统提示 | 读者只能从集成测试间接推断 mode、目录、模板和 loaded context |
| `AgentServiceTest` 没有锁定所有 Advisor 的绝对顺序 | 未来调整 order 可能仍通过相对关系测试，却改变 Spring AI Tool Calling 语义 |
| `AgentServiceTest` 没有锁定 `SkillTools` 被注册为 ChatClient 默认工具 | Demo 无法直接展示 `@Tool` Bean 如何进入 `ChatClient` |
| 没有把 backend/frontend 工具边界写成稳定文档中的测试入口 | 读者容易把两套模板或两套工具误认为同一实现 |

## 3. 设计决策

### 3.1 只增加可观察契约，不重构 Agent

本轮不引入新的 Prompt builder、Advisor registry、Skill session abstraction 或
Spring AI 2.x API。原因是现有实现已经能完成普通 Agent 的同步、流式和写操作闭环，
缺口主要是“测试和文档没有把关键行为直接展示出来”。

可逆边界：如果未来出现第三种运行模式，先扩展模式枚举和对应模板测试；不要在本轮把
frontend/backend 规则合并成一套模糊提示。

### 3.2 `SkillsAdvisorTest` 使用真实资源和真实 registry

测试不 mock `SkillRegistry` 或 `PromptLoader`，而是使用当前 classpath Skill 和模板，
通过 `ChatClientRequest.builder()` 构造 Spring AI 请求，直接调用 `before(...)`，观察：

- Level 1 目录包含当前所有 Skill；
- backend 模式包含 backend 写操作规则和结构化参数示例；
- backend 模式不包含 frontend 的“唯一可用 HTTP 工具/字符串 params”规则；
- frontend 模式包含 frontend 规则；
- frontend 已加载 Skill 时包含对应 Level 2 正文；
- 缺省或未知 mode 安全回退到 frontend；
- 认证 token 等非 prompt context 不被注入系统提示。

这是单元级 Prompt 契约，不替代 `BackendApiIntegrationTest` 的完整 Tool Calling 回合。

### 3.3 `AgentServiceTest` 锁定绝对 order 和默认工具注册

将现有 order 常量改为包可见的 `static final` 常量并保留清晰注释，测试直接断言：

```text
SkillsAdvisor                 Integer.MIN_VALUE
MessageChatMemoryAdvisor      Integer.MIN_VALUE + 100
VectorStoreChatMemoryAdvisor  Integer.MIN_VALUE + 150（启用时）
QuestionAnswerAdvisor         Integer.MIN_VALUE + 200（启用时）
ToolCallAdvisor               Integer.MIN_VALUE + 300
```

测试同时捕获 `builder.defaultTools(...)` 的参数，证明传入的对象就是构造函数收到的
`SkillTools` 实例。

原因：

- Advisor order 是当前项目解决“记忆/RAG 不进入中间 tool-call JDBC 写入”的关键实现；
- `defaultTools(skillTools)` 是 Spring AI Tool Calling 教学主线的直接证据；
- 这些断言不访问网络，也不改变运行时接口。

可逆边界：未来调整 order 时，必须同时更新规划、测试、架构文档和学习主线，并通过
普通 Scripted `ChatModel` 集成测试；不能只改一个数字。

## 4. 一次性验收测试设计

### 4.1 `SkillsAdvisorTest`

新增：

`src/test/java/com/example/demo/agent/SkillsAdvisorTest.java`

测试场景：

1. `backendModeInjectsBackendRulesAndStableLevelOneCatalog`
   - 构造 backend context；
   - 调用 `before(...)`；
   - 断言系统提示包含 `search-products`、`swagger-petstore-openapi-3-0`、
     `buildHttpRequest`、结构化 `pathParams/queryParams/body`；
   - 断言不包含 frontend 模板特有的完整规则
     `参数都是**字符串**：\`params\`、\`body\` 都是 JSON 字符串`；
   - 断言 Skill name 顺序与 `registry.all().keySet()` 一致。
2. `frontendModeInjectsBrowserRulesAndLoadedSkillBody`
   - 通过 `SkillCoreTools.loadSkill("search-products")` 准备 frontend 状态；
   - 断言包含 frontend 规则、已加载正文和“禁止重复调用”；
   - 断言不包含 backend 的 `buildHttpRequest` 写操作规则；
   - 测试结束调用 `reset()`，避免 singleton 测试污染。
3. `UnknownOrMissingModeFallsBackToFrontendRules`
   - 缺省 context 和未知字符串分别调用 `before(...)`；
   - 断言都采用 frontend 规则；
   - 断言不会误选 backend 写操作规则。
4. `AdvisorDoesNotCopyAuthenticationLikeContextValuesIntoSystemPrompt`
   - 将模拟 token 放入普通 context；
   - 断言系统提示不包含 token；
   - 只验证 Prompt 文本，不把“token 是否传给工具”与 Advisor 责任混淆。

测试辅助方法只负责构造 `ChatClientRequest` 和提取系统提示，不测试私有方法。若需要
脱离 Spring Context 初始化 `SkillRegistry`，允许复用现有测试中的
`DefaultResourceLoader` 字段注入和 `init()` fixture；该反射只用于补齐测试生命周期，
不得用来调用或修改待验证的私有业务行为。

### 4.2 `AgentServiceTest`

扩展：

`src/test/java/com/example/demo/service/AgentServiceTest.java`

测试场景：

1. `keepsAllEnabledAdvisorsInTheDocumentedAbsoluteOrder`
   - 开启 vector memory 和 RAG；
   - 使用真实 `SkillsAdvisor`，或对 Mockito mock 显式设置
     `getOrder()` 为 `Ordered.HIGHEST_PRECEDENCE`；
   - 断言 advisor 顺序名称和 order 与设计表一致。
2. `omitsOptionalRetrievalAdvisorsWhenDisabled`
   - 关闭两个开关；
   - 断言只存在 Skills、JDBC memory、ToolCall；
   - 断言 ToolCall 仍在末尾。
3. `registersTheProvidedSkillToolsAsChatClientDefaultTools`
   - 捕获 `defaultTools`；
   - 断言包含传入的同一个 `SkillTools` 实例。

现有测试保留，避免把测试从“类型存在”替换成单一实现细节断言。

## 5. 生产代码与文档修改范围

### 必须修改

- `src/main/java/com/example/demo/service/AgentService.java`
  - 将 Advisor order 常量调整为包可见；
  - 增加一段简短的顺序说明，解释记忆/RAG 在 Tool Calling 前构造的原因；
  - 不改变数值、Bean、方法签名或运行时行为。
- 新增 `src/test/java/com/example/demo/agent/SkillsAdvisorTest.java`。
- 扩展 `src/test/java/com/example/demo/service/AgentServiceTest.java`。

### 教育文档

- `docs/ARCHITECTURE.md`：补充普通 Agent Advisor 绝对顺序表，并链接两个测试。
- `docs/learning-path.md`：在 ChatClient/Tool Calling 主线中增加“先看 Advisor 契约测试”
  的入口，明确 backend/frontend 模式区别。
- `docs/HARNESS.md`：增加 `SkillsAdvisorTest` 和 `AgentServiceTest` 的专项命令及通过
  标准。
- `AGENTS.md`：在普通 Agent 章节和当前加固规划索引中增加近距离链接。
- `docs/drafts/README.md`：登记本规划。
- 本文实施完成后补充实施记录、三轮规划检查和三轮实现审查结果。

### 明确不修改

- `src/main/resources/skills/**`
- `src/main/resources/prompts/skills-advisor/**`
- `SkillTools`、`SkillRegistry`、`SkillLoadSession` 的运行逻辑
- `ProductService`、Controller、数据库 schema
- `frontend/**`
- `src/main/java/com/agui/**`、`ag-ui-4j`、`spring-ai-agent-utils`
- Spring Boot/Spring AI 版本

## 6. 实施顺序

1. 完成本文规划三轮连续无修改检查。
2. 先新增 `SkillsAdvisorTest` 和扩展 `AgentServiceTest`，此时测试应先证明当前行为；
   若测试暴露既有错误，记录为实施前契约问题并在生产代码中做最小修复。
3. 对 `AgentService` 只做 order 常量可见性和教育性注释改动。
4. 更新稳定文档和草稿索引。
5. 执行基本硬门槛：

   ```bash
   git diff --check
   mvn clean compile test-compile
   mvn -Dtest='SkillsAdvisorTest,AgentServiceTest,SkillToolsTest,BackendApiIntegrationTest,ChatControllerTest' test
   ```

6. 运行默认 `mvn test`，确认不访问真实 LLM/Embedding/PostgreSQL。
7. Mock/确定性测试通过后，按用户已授权的根目录 `.env` 凭证执行真实 LLM 验证：
   - 运行一次最小普通 `/api/chat/text` Skill 查询，确认真实模型能看到 backend
     Prompt 并完成 `loadSkill -> httpRequest(GET) -> 最终回答`；
   - 运行传统嵌入式 Web UI 的 Playwright 只读闭环，确认页面 DOM 展示真实商品结果；
   - 持续观察后端日志、HTTP JSON 和工具回合，失败时区分模型/数据库/实现问题；
   - 不覆盖 AG-UI/CopilotKit，不打印或提交 API key。
8. 通过硬门槛后，进行三轮固定范围实现审查：
   - 正确性/兼容性：Prompt 模式选择、order、工具注册和测试断言；
   - 安全/一致性：token 不入 prompt、frontend/backend 不混用、singleton reset；
   - 测试/交付：范围、文档链接、命令、未修改的 AG-UI 边界。
9. 更新本文状态和验证记录，commit、push，确认主仓库和子模块干净。

## 7. 风险、回滚和中断恢复

### 风险

- Spring AI 1.1.8 的 `ChatClientRequest`/`Prompt` API 若与本地依赖不一致，测试应优先
  依据本地源码和编译错误修正测试写法，而不是引入兼容层。
- `SkillCoreTools` 是 singleton，frontend Prompt 测试若忘记 `reset()` 会造成测试顺序
  依赖；测试必须使用 `try/finally` 或 `@AfterEach` 清理。
- 过度锁定中文提示词全文会使未来措辞优化成本过高；测试只断言模式边界、关键工具
  名称、关键参数和 Skill 内容，不断言整段 Prompt 等于固定快照。

### 回滚

- 若只想回滚教学性改动，可回滚测试和文档，不影响生产。
- 若 order 可见性注释引发兼容性问题，可保留 private 常量并在测试中使用
  `Advisor.getOrder()` 断言；不改变 order 数值。
- 不得通过删除测试或放宽 backend/frontend 断言来“修复”失败。

### 中断恢复

从本文实施顺序中第一个未完成步骤继续。恢复时先运行：

```bash
git status --short --branch
git diff -- docs/drafts/advisor-and-tool-loop-contract-hardening-plan.md
```

不要重新创建第二份规划，也不要把 AG-UI/CopilotKit 重新纳入本轮。

## 8. 规划检查记录

规划检查使用从 `0` 开始的计数器。只有连续三轮实际完成且无修改才允许进入实施。
每轮检查前后都要重新核对相关源码和文档；如果发现实质问题，先修改本文，再将
计数器重置为 `0`。

为遵守“无问题轮次不写文档”的规则，本节不预填或回写干净轮次的结果。每轮检查的
时间、范围、发现问题、处理措施和计数器状态由当前任务的进度更新记录；只有发现问题
时才修改本文。规划实施完成后，本节可在下一次独立文档更新中整理历史摘要，但该更新
不属于规划检查循环。

## 9. 实施记录

### 9.1 实际修改与偏差

本轮按规划实施，未扩大到 AG-UI/CopilotKit 或 `spring-ai-agent-utils`：

- `AgentService` 的四个 Advisor order 常量改为包可见，数值保持不变，并增加
  Tool Calling 链顺序注释。
- 新增 `SkillsAdvisorTest`，使用真实 classpath Skill、Prompt 模板和 registry，验证
  backend/frontend/未知模式、Level 1 目录、frontend 已加载正文和 token 不入系统提示。
- 扩展 `AgentServiceTest`，验证启用/禁用可选 Advisor 的绝对顺序以及
  `.defaultTools(skillTools)` 注册。
- 更新 `AGENTS.md`、`docs/ARCHITECTURE.md`、`docs/HARNESS.md`、
  `docs/learning-path.md` 和本目录索引，增加近距离源码、测试和验证入口。

未修改本规划列出的 Skill 资源、Controller、数据库 schema、前端、AG-UI 源码和两个
子模块。

### 9.2 验证结果

测试顺序遵循 Mock/确定性优先，再执行真实模型和传统页面闭环：

| 验证 | 结果 |
|---|---|
| `git diff --check` | 通过 |
| `mvn clean compile test-compile` | 通过 |
| 测试先行：`SkillsAdvisorTest`、`AgentServiceTest` | 8/8 通过 |
| 本轮相关后端测试 | 52/52 通过 |
| 默认 `mvn test` | 70/70 通过 |
| `OpenAiCompatibleApiLiveTest` | 1/1 通过 |
| PostgreSQL profile 真实启动 | 通过；6 个 Skill、24 个 API index 初始化成功 |
| 真实普通 `POST /api/chat/text` | 通过；完成真实商品查询，无重复工具循环 |
| 传统嵌入式 Web UI 真实 Playwright | 通过；登录、聊天 JSON 和商品结果 DOM 均验证 |
| 前端 `npx tsc --noEmit` | 通过 |
| 前端 `npm run test:repository` | 通过 |
| 前端 `npm run build` | 通过；仅有现有 CopilotKit 依赖 warning |
| Skills URL Mock Playwright | 通过 |
| 传统页面 Mock Playwright | 通过 |

真实验证使用根目录 `.env` 中用户授权的本地配置；密钥未输出、未写入文档或提交。
本轮不覆盖 AG-UI/CopilotKit 运行链路。

### 9.3 实现后三轮收敛审查

在完成硬门槛、确定性测试、真实 LLM 和传统页面验证后，按固定范围完成三轮只读审查：

1. 正确性/兼容性：检查 `SkillsAdvisor` 模式选择、Prompt 资源、Advisor 绝对顺序、
   `defaultTools` 注册和测试断言；未发现问题。
2. 安全/一致性：检查请求级 Skill/确认状态、认证 token ToolContext 边界、前后端
   Prompt 隔离和 singleton 测试清理；未发现问题。
3. 测试/交付：检查测试覆盖、文档发现入口、命令可复现性和 AG-UI 排除边界；发现
   本文状态和实施记录未更新，已修正后重新计数。修正后的三轮复核均未发现问题。

最终收敛结果：连续三轮未发现影响正确性、成本安全、兼容性、数据一致性或交付完整性
的问题，未再修改代码。

### 9.4 提交与恢复状态

提交和 push 在本轮工作全部完成后执行。提交前必须确认主仓库、`ag-ui-4j/` 和
`spring-ai-agent-utils/` 子模块均无工作区改动，且不提交 `.env` 或构建产物。
