# 当前项目 SKILL 支持改进规划

> **状态**: P0 与普通 Agent 的 P1-B 已实施并通过离线硬门槛；P1-C/P2/P3 按本文规划继续推进
> **目的**: 在不直接替换生产链路的前提下，系统改进当前项目运行时 Skills 的正确性、安全性、可测试性、可发现性和未来迁移能力。
> **最后核对**: 2026-08-23
> **规划对应审计**: [Spring AI Community `SkillsTool` 审计报告](../spring-ai-agent-utils-audit.md)
> **执行规则**: 本文依照根 [AGENTS.md 的规划与文档评审原则](../../AGENTS.md#规划与文档评审原则)
> 编写和维护；每次继续实施前都必须重新核对源码、测试和本文状态，并完成规划/实现的
> 三轮连续无修改检查。

## 1. 决策摘要

当前项目继续使用：

```text
SkillRegistry
  -> SkillsAdvisor
  -> SkillTools（普通 Agent）
  -> SkillCoreTools（AG-UI 后端）
  -> 浏览器 httpRequest（AG-UI 业务 API 执行）
```

本规划不建议现在把生产实现替换为社区 `SkillsTool`。社区库固定参考基线为：

```text
spring-ai-community/spring-ai-agent-utils
release: v0.10.0
commit: 7f8bc47de1bc5a306b6cb078fa6b191ff7845572
path: spring-ai-agent-utils/
```

推荐路线是：

1. 先补当前实现的安全和正确性底座；
2. 再把资源来源、frontmatter 和测试组织抽象清楚；
3. 在不改变两条 Agent 链路职责的情况下增强渐进式披露；
4. 最后再做 Spring AI 2.x + 社区库平面 Skill PoC；
5. PoC 未通过兼容性、性能、安全和回滚验收前，不替换当前生产实现。

## 2. 当前基线与不可误读的边界

### 2.1 事实来源

实施者恢复任务时，按以下顺序核对：

| 事实 | 当前来源 |
|---|---|
| Spring Boot、Spring AI、Java 版本 | `pom.xml` |
| Skill 资源 | `src/main/resources/skills/` |
| Skill 元数据模型 | `src/main/java/com/example/demo/model/Skill.java` |
| Skill 发现与 API index | `src/main/java/com/example/demo/agent/SkillRegistry.java` |
| Prompt 渐进披露 | `src/main/java/com/example/demo/agent/SkillsAdvisor.java` |
| 普通链路工具 | `src/main/java/com/example/demo/agent/SkillTools.java`、`AgentService.java` |
| AG-UI 后端工具 | `SkillCoreTools.java`、`AgUiConfig.java`、`AgUiController.java` |
| 浏览器业务 API 执行和 URL 校验 | `frontend/hooks/useHttpRequestTool.tsx` |
| 对外 API | `src/main/java/com/example/demo/controller/`、`docs/rest-api.md` |
| 外部实现参考 | `spring-ai-agent-utils/` 子模块及 [审计报告](../spring-ai-agent-utils-audit.md) |

### 2.2 当前 Skill 分层

- Level 1：`SkillsAdvisor` 将全部 Skill 的 `name` 和 `description` 放入系统提示；
- Level 2：`loadSkill(name)` 返回完整 `SKILL.md` 和 `links` 提示；
- Level 3：`readSkillReference(name, relativePath)` 读取 Skill 的 `references/` 内容；
- API 执行：普通链路由 Java 端 `SkillTools.httpRequest` 执行；AG-UI 后端只暴露
  `loadSkill`/`readSkillReference`，由浏览器侧 `httpRequest` 携带 Token 并处理写操作确认。

### 2.3 P0 实施后的当前状态

本次已完成并验证的 P0 范围：

- `SkillRegistry` 使用结构化 Jackson YAML frontmatter 解析，要求文档开头的独占
  `---` 分隔线，校验 `name`、`description`、`links`，保留 `license`、`metadata` 和
  额外 metadata；重复 Skill name 和 API index 冲突会启动失败。
- `SkillReferenceReader` 将 Level 3 限制在已注册 Skill 的 `references/` 下，拒绝未知
  Skill、绝对路径、反斜杠、空路径段、`.`、`..`、编码的点号/分隔符、过长路径和超大
  资源；读取上限为 64 KiB，模型返回上限为 4000 字符，底层资源错误不向模型暴露主机
  路径，普通 Agent 与 AG-UI 共用同一实现。
- `SkillRegistry` 建立稳定排序的 API index，集中处理 method、相对路径、查询串、
  fragment、路径参数、绝对 URL、目录跳转和未索引端点校验；路径参数替换后还会再次
  校验最终 URL，防止模板校验被替换值绕过。
- `AgUiController` 暴露 `hierarchical` 和 `referencePath`，`SpringAIAgent` 委托同一
  后端校验；前端 `api-index-validation.mjs` 使用同等的 exact/template matching 语义，
  不再自动纠正或执行绝对 URL。
- `PromptLoader` 的 Java fallback 已与当前 AG-UI 唯一浏览器 `httpRequest` 工具契约
  对齐，资源文件缺失时不会退回旧的 `buildHttpRequest`/代码块确认流程。

仍未完成的缺口：

- 普通 Agent 已使用每次同步/流式调用独立的 `SkillLoadSession` 和 `ToolContext`，并有
  单测及 Spring Context 工具回合证据；AG-UI 的 `SkillCoreTools` 仍保留进程级状态，本轮
  明确不重构或验收该链路。若未来要统一两条链路，需单独设计 run/thread context。
- 尚未抽象 `SkillSource`/`SkillProvider`，也没有独立 filesystem/classpath/JAR fixture
  测试；这是 P1-C。
- `SkillsAdvisor` 尚未加入 Level 1/2/3 指标、links 去重/循环保护、能力分类或风险标签；
  这是 P2-A/P2-C。
- 尚未升级 Spring AI 或直接引入社区库；Spring AI 2.x + 社区 `SkillsTool` 仍只允许在
  独立 PoC 中评估。

## 3. 目标、非目标与成功定义

### 3.1 目标

到规划实施完成后，当前项目应具备：

1. 可校验、可诊断的 Skill frontmatter 契约；
2. 不允许目录穿越的受限 `readSkillReference`；
3. 单一、可测试、被普通 Agent 和 AG-UI 共用的 API index 校验规则；
4. 普通 Agent、AG-UI、同步和流式请求的 Skill 状态隔离证据；
5. classpath、文件系统和未来可复用 Skill 包的明确资源来源模型；
6. Level 1/2/3 渐进披露的确定性测试和 Token/命中率评估入口；
7. 读者可以从稳定导航发现知识库路径、运行时 Skills 路径、社区审计和本规划；
8. Spring AI 2.x 未来 PoC 与当前生产链路隔离，结果可复现、可回滚。

### 3.2 非目标

- 本阶段不升级主项目 Spring Boot 或 Spring AI；
- 不把社区库加入当前项目运行时依赖；
- 不直接改用社区 `SkillsTool` 的通用 FileSystem/Shell 工具；
- 不把知识库 RAG 和可执行 API Skill 合并成一种资源格式；
- 不重写 AG-UI 或 CopilotKit 的工具协议；
- 不把所有历史 `docs/drafts/` 重写为稳定文档；
- 不在没有真实兼容性证据时宣称已支持 Spring AI 2.x。

### 3.3 成功定义

P0/P1 阶段完成的最低证据：

```text
mvn clean compile test-compile
mvn -Dtest='SkillRegistryTest,SkillReferenceReaderTest' test
cd frontend && npm run build
cd frontend && npx tsc --noEmit
cd frontend && npm run test:skills
git diff --check
```

并且测试能够证明：错误 frontmatter 可诊断、非法 references 路径被拒绝、合法
references 可读取、API index 能区分方法和路径参数；普通 Agent 的每次同步/流式调用
使用独立 loaded state，AG-UI 状态仍按本规划的非目标边界单独记录。专门的并发压力测试
仍是后续测试增强项，不能由当前集成回合替代。

### 3.4 本次实施记录

本次已落地的文件边界：

| 范围 | 实现/测试证据 | 状态 |
|---|---|---|
| frontmatter、Skill 注册和 API index | `SkillRegistry.java`、`Skill.java`、`SkillDefinitionException.java`、`SkillRegistryTest.java` | 已完成 |
| Level 3 reference 安全 | `SkillReferenceReader.java`、`SkillTools.java`、`SkillCoreTools.java`、`SkillReferenceReaderTest.java` | 已完成 |
| Java/AG-UI URL 校验 | `SkillRegistry.java`、`SpringAIAgent.java`、`AgUiController.java` | 已完成 |
| 浏览器 API index 校验 | `frontend/lib/api-index-validation.mjs`、`useHttpRequestTool.tsx`、`frontend/tests/skills-url-mock.mjs` | 已完成 |
| Prompt fallback 兼容 | `PromptLoader.java` | 已完成 |
| `.env` 一键开发环境 | `dev.sh` | 已完成 |
| 传统页面真实浏览器验收 | `frontend/tests/traditional-ui-e2e.mjs`、`frontend/package.json` | 已完成 |
| Embedding provider 参数兼容 | `EmbeddingModelConfig.java`、`application.yml` | 已完成 |
| 普通 Agent turn context | `SkillLoadSession`、`AgentService`、`SkillToolsTest`、`BackendApiIntegrationTest` | 已完成 |
| 资源 provider/JAR、指标 | 本文 P1/P2 设计 | 未实施 |

离线硬门槛结果（2026-08-23）：

```text
mvn clean compile test-compile                         PASS
mvn -Dtest='SkillRegistryTest,SkillReferenceReaderTest' test
  SkillRegistryTest: 5 passed
  SkillReferenceReaderTest: 5 passed
  total: 10 passed                                     PASS
cd frontend && npx tsc --noEmit                       PASS
cd frontend && npm run build                          PASS
cd frontend && npm run test:skills                    PASS
git diff --check                                      PASS
```

真实服务补充验证（2026-08-23，用户已授权读取 main 工作区 `.env`）：

- 直接用同一 `SILICONFLOW_API_KEY` 调用 `/v1/models` 返回 HTTP 200；调用
  `BAAI/bge-m3` Embedding 时，不带 `dimensions` 返回 HTTP 200，带
  `dimensions: 1024` 返回 HTTP 400 `parameter is invalid`。因此
  `EmbeddingModelConfig` 增加了 `SILICONFLOW_DIMENSIONS_ENABLED` 开关，默认不发送
  该参数；API key 仍只记录 `apiKeyConfigured=true/false`。
- `./dev.sh` 已实际读取 `.env` 启动：默认 `postgresql` profile、PostgreSQL JDBC
  连接成功、pgvector schema 可用、3 篇知识库文档加载成功；脚本将
  `POSTGRES_USER`/`POSTGRES_PASSWORD` 映射为 Spring datasource 凭证，并健康检查
  后端 `8080` 和前端 `4000`。`./dev.sh --stop` 能精确停止两个端口。
- 使用内嵌传统页面 `/` 的 Playwright headless Chromium E2E：
  页面 DOM 和登录通过，`GET /api/auth/verify` 返回 HTTP 200，页面发起
  `POST /api/chat/text` 并返回 HTTP 200；页面消费 JSON 后在 DOM 显示商品结果。
  后端同一轮日志显示 `loadSkill` 1 次、`httpRequest` 1 次，没有重复工具循环。
- 另用 curl 验证 `GET /api/auth/verify` 的 JSON `valid=true`，并调用
  `/api/chat/text` 得到 HTTP 200、非空 `response` 和实际商品表格。该普通 Agent
  真实闭环覆盖了本次 Skill 的 Skill 选择、API 调用和结果呈现。
- 此外，前一轮已完成独立 AG-UI 真实 SSE 工具交接烟测；本次不把它冒充为传统页面
  闭环，也不宣称未执行浏览器 `respond()` 的 AG-UI 后续回合已完成。

## 4. 推荐实施顺序

### P0-A：建立 Skill 文档契约与 frontmatter 校验（已完成）

**目标**：在不改变现有业务 Skill 内容语义的前提下，先让输入数据可靠。

**建议修改边界**：

- `src/main/java/com/example/demo/model/Skill.java`
- `src/main/java/com/example/demo/agent/SkillRegistry.java`
- `src/main/resources/skills/*/SKILL.md`
- 新增 `src/test/java/com/example/demo/agent/SkillRegistryTest.java` 或等价测试；
- 如需统一错误，新增 `SkillLoadException`/校验结果模型，但不要把异常直接暴露成
  Stack Trace 给模型。

**推荐默认**：

- 使用现有 Jackson YAML 能力，不引入第二个 YAML parser；
- frontmatter 必须是文档开头的 `---` 块，结束标记必须独占一行；
- `name`、`description` 必填；`name` 只允许小写字母、数字和连字符，长度上限
  默认 64；`description` 长度上限默认 1024；
- `version` 可选，但存在时保留原始字符串；
- `links` 解析成当前 `SkillLink` 列表；缺失、空列表和未知 metadata 字段分别定义
  行为；
- 对未知字段采用“保留但不参与执行”的兼容策略，避免丢失 OpenAPI metadata；
- 重复 Skill name 默认启动失败并指出来源文件，而不是静默覆盖。

**验收**：

- flat Skill、带 links Skill、带 metadata Skill、缺字段、错误 YAML、重复 name
  各有确定性测试；
- 启动日志能区分“未发现资源”“发现但解析失败”“成功注册”；
- 没有把 frontmatter 原文全部注入 Level 1 prompt；
- 当前六个 Skill 仍能加载并生成原有 API index。

**回滚边界**：只要新校验使现有 Skill 内容无法加载，先修复 Skill 文件或提供兼容
解析，不放宽到静默接受错误输入；整个阶段可以独立回滚，不影响工具执行代码。

### P0-B：修复 `readSkillReference` 的路径和大小安全（已完成）

**目标**：把 Level 3 明确限制在 `skills/<skillName>/references/` 内。

**建议修改边界**：

- `SkillTools.readSkillReference`
- `SkillCoreTools.readSkillReference`
- 推荐新增共享的 `SkillReferenceReader`，由两个工具委托，避免两条链路规则漂移；
- 新增测试覆盖文件系统和 classpath 资源行为。

**推荐校验顺序**：

1. Skill name 必须来自 `SkillRegistry`；
2. `relativePath` 非空、不能是绝对路径；
3. 统一 `/` 分隔符，拒绝 `\`、空路径段和 `..`；
4. 解析后路径必须仍位于该 Skill 的 `references/` 根；
5. 只允许常规文件，拒绝目录、符号链接越界和不存在资源；
6. 统一最大内容大小，默认沿用 4000 字符作为模型返回上限，同时把读取上限与
   返回上限分开配置；
7. 返回结构化、可诊断但不泄露主机绝对路径的错误信息。

**验收**：

- 合法 `operations/addPet.md` 可读；
- `../SKILL.md`、`../../application.yml`、`/etc/passwd`、反斜杠路径和编码绕过被拒绝；
- 不存在文件、目录、超大文件有不同测试；
- `SkillTools` 与 `SkillCoreTools` 的结果一致；
- 不把整个 Skill 根目录、绝对 filesystem path 或任意 Shell 能力暴露给模型。

**回滚边界**：保留工具名和参数名；若底层 Resource 在 fat JAR 下行为不一致，只替换
资源解析适配，不降低路径限制。

### P0-C：集中 API index、URL 和方法校验（已完成）

**目标**：让 Skill 文档描述、Java 请求和浏览器请求共享同一份可验证规则。

**建议修改边界**：

- `SkillRegistry` 的索引模型和匹配方法；
- `AgUiController.apiIndex()`；
- `frontend/hooks/useHttpRequestTool.tsx`；
- `SpringAIAgent` 中已有 API 校验逻辑；
- `docs/rest-api.md`、运行时 Skill 文件和对应 Controller；
- 新增 Java 和 TypeScript/浏览器侧确定性测试。

**推荐默认**：

- API index 条目至少包含 `skillName`、HTTP method、模板 path、description、
  hierarchical/reference 信息；
- method 统一为大写，只允许当前支持的 GET/POST/PUT/PATCH/DELETE；
- 路径模板按 segment 匹配，精确路径优先，参数模板次之；查询参数不参与路径模板
  匹配，但按 Skill 文档定义进行 schema 校验；
- 同一 `METHOD + path` 冲突默认启动时失败并列出来源 Skill；
- 未被 API index 注册的相对 URL 默认拒绝；绝对 URL 默认也应按 allowlist 决定，
  不能只因以 `http` 开头就放行；
- Java 与浏览器侧不各自发明“自动纠错”规则；当前默认严格拒绝未索引、绝对、非法
  或路径参数未解析的 URL，不自动改写模型提供的路径。

**验收**：

- `/api/products/{id}` 能匹配 `/api/products/3`；
- 错方法、错路径、跨 Skill 猜测和未索引路径被拒绝；
- 公开 API 与受保护 API 的认证/确认边界没有因校验重构改变；
- API index endpoint、Java agent 和浏览器 hook 使用同一语义。

**回滚边界**：先以只读校验和日志模式上线测试，确认误拒绝率后再强化生产拒绝；
不要在没有 index 诊断信息时让模型陷入重复调用。

### P1-A：补齐确定性测试基础设施（已完成最小基线，仍需扩展）

**目标**：让 Skills 逻辑脱离外部 LLM 也可验证。

**最低测试矩阵**：

| 组件 | 必测行为 |
|---|---|
| `SkillRegistry` | 平面/分层发现、frontmatter、links、metadata、重复名、索引冲突、路径参数 |
| `SkillsAdvisor` | Level 1 catalog、Level 2 loaded context、空状态、顺序和 prompt 边界 |
| `SkillTools` | 合法/非法 `loadSkill`、reference、HTTP URL 校验、响应截断 |
| `SkillCoreTools` | 与普通链路共享规则、错误输入、写操作提醒 |
| API index | exact match、template match、错方法、未知路径、查询串 |
| 资源加载 | filesystem、classpath、打包 JAR；可选 fat JAR |
| 工具回调 | `JsonArgToolCallback` 的 deterministic JSON 参数适配，不调用真实模型 |

**测试原则**：

- 使用临时目录、内存 Resource 和 mock `RestTemplate`；
- 不把 OpenAI、MiniMax、Embedding、PostgreSQL 作为单元测试前置；
- 外部 LLM 烟测继续保留，但在文档中明确不是离线质量门槛；
- 测试名称描述行为，不只描述实现方法；
- 任何修复先添加能复现问题的回归测试。

本次已完成 `SkillRegistryTest`、`SkillReferenceReaderTest`、`SkillToolsTest` 和
`BackendApiIntegrationTest` 的离线基线、前端 Mock Playwright 的 API index/DOM/网络/
访问性断言，以及传统页面真实 Playwright E2E；独立的 `SkillsAdvisor` 测试和
JAR/fat-JAR fixture 仍按本节矩阵留待 P1 扩展。

### P1-B：验证 loaded Skill 状态隔离

**背景**：普通 Agent 曾将 `loadedSkills` 放在 Spring 单例字段中；当前普通链路已经改为
通过每次调用创建的 `SkillLoadSession` 放入 Spring AI `ToolContext`。剩余工作只针对
AG-UI 的 `SkillCoreTools` 进程级状态和两条链路未来是否需要统一上下文。

**需要验证**：

- 为普通 `/api/chat` 增加并发压力测试，证明两个请求不能看到对方已加载 Skill；
- 为同一 conversation 的同步与 stream 增加一致性测试；
- AG-UI 请求与普通 Agent 请求的状态隔离仍需单独设计和验收；
- 为工具调用重跑、请求异常、取消和超时补充生命周期测试。

本次实施已为普通 Agent 提供独立 `SkillLoadSession.markLoaded()`，重复 Skill 只在当前
ToolContext 会话中去重；同步和流式请求均不依赖全局 loaded 列表。AG-UI 仍使用
`SkillCoreTools` 的既有状态，本轮不把它宣称为已完成的并发隔离。

**推荐默认**：先用请求范围的 `SkillTurnContext` 替代共享 `List`，由
`SkillTools`/`SkillCoreTools` 委托；如果 Spring request scope 不覆盖异步 Agent
生命周期，则显式将 context 绑定到 conversation/run，而不是依赖线程本地变量。

**可逆边界**：在完成并发测试前，不改变现有对外工具名称；可以先增加诊断和隔离层，
再删除旧字段。

### P1-C：借鉴社区库的 Skill source/provider 与 JAR 测试

**目标**：吸收社区库在资源发现和测试组织上的优点，但保持当前业务约束。

**参考代码**：

- `spring-ai-agent-utils/spring-ai-agent-utils/src/main/java/org/springaicommunity/agent/utils/Skills.java`
- `spring-ai-agent-utils/spring-ai-agent-utils/src/main/java/org/springaicommunity/agent/tools/SkillsTool.java`
- `spring-ai-agent-utils/spring-ai-agent-utils/src/test/java/org/springaicommunity/agent/tools/SkillsToolTest.java`

**推荐设计**：

```text
SkillSource
  -> FileSystemSkillSource
  -> ClasspathSkillSource
  -> OptionalJarSkillSource
  -> SkillRegistry
```

先只支持当前需要的 classpath 和 filesystem；JAR/SkillsJar 作为可选能力，必须有来源
标识、版本、优先级和重复 name 冲突策略。不要把社区子模块路径硬编码到运行时。

**验收**：

- 主项目仍只扫描配置的资源；
- 可复用 Skill 包不会绕过 frontmatter、API index 和 reference 安全校验；
- 多来源同名冲突有确定性结果；
- 资源扫描失败不会静默切换到错误目录；
- 主仓库 Maven 构建不因社区子模块存在而编译它。

### P2-A：增强渐进披露和 Skill 发现

**目标**：在保持 Token 节省目标的同时，让模型更容易正确发现能力。

**推荐 Level 设计**：

| Level | 内容 | 进入时机 |
|---|---|---|
| 1 | name、description、version、能力摘要、风险标签 | 每轮系统提示 |
| 2 | SKILL.md 正文、结构化 links、API 操作总览 | `loadSkill` |
| 3 | references/resources/operations/schemas 的单文件内容 | `readSkillReference` |

实施点：

- 给 frontmatter 增加可选的能力分类、只读/写入标志和版本兼容信息，但先不把它们
  当作安全授权；
- links 用结构化对象渲染，去重并阻止循环提示；
- 对关联 Skill 使用有限深度或“提示而不自动加载”；
- 对大量 Skill 增加稳定排序或分类，避免 ConcurrentHashMap 顺序造成 prompt 漂移；
- 记录 Level 1/2/3 的字符数、加载耗时和命中情况；
- 单独评估 Spring AI `ToolSearchToolCallingAdvisor` 对 Java ToolCallback 的价值，
  不把 Markdown Skill 迁移和 Tool Search 混成一个改造。

**验收**：

- 目录 prompt 不包含完整正文；
- 一个 Skill 的 Level 2 不会重复注入多次；
- links 循环、重复和未知目标不会造成无限加载；
- 能用 deterministic fixture 比较加载前后上下文大小。

### P2-B：明确知识库、运行时 Skill 与 Agent Skill 的发现路径

稳定入口保持三分：

```text
公司政策/保修/服务条款
  -> docs/knowledge-and-skills.md
  -> KnowledgeBaseInitializer / QuestionAnswerAdvisor

查询订单、创建售后、申请退款等服务动作
  -> docs/knowledge-and-skills.md
  -> src/main/resources/skills/
  -> SkillRegistry / SkillsAdvisor / tool boundary

仓库开发工作流
  -> AGENTS.md
  -> .agents/skills/project-docs/SKILL.md
```

社区实现只通过：

```text
docs/README.md
  -> docs/spring-ai-agent-utils-audit.md
  -> spring-ai-agent-utils/ @ v0.10.0
  -> 本规划
```

任何未来升级或 PoC 文档都必须从这条链路进入，不能让读者从历史草稿中猜当前方案。

### P2-C：可观测性与脱敏

建议增加结构化事件或 metrics：

- Skill 资源发现数量、成功数、失败数、跳过数；
- frontmatter 校验失败；
- Level 1 prompt 大小；
- `loadSkill`、`readSkillReference` 调用次数与耗时；
- reference 拒绝原因；
- API index 命中、拒绝和未索引 URL 次数；当前不记录自动纠正，因为生产实现不再改写 URL；
- 普通 Agent 与 AG-UI 的工具调用结果。

日志规则：

- 不记录 Skill 正文、知识库全文、Authorization、Token、请求体中的敏感字段；
- 错误消息使用 Skill name 和相对资源标识，不暴露主机绝对路径；
- 生产日志保留聚合信息，调试模式才允许有限的非敏感细节。

### P3：Spring AI 2.x + 社区 `SkillsTool` 隔离 PoC

**触发条件**：

- 主项目明确决定升级 Spring Boot 4.x / Spring AI 2.x；
- 升级有独立分支或独立模块；
- AG-UI、CopilotKit、模型 Provider 和工具参数适配已有兼容验证计划。

**PoC 结构**：

```text
独立 demo/module
  -> Spring Boot 4.x
  -> Spring AI 2.x
  -> spring-ai-agent-utils 0.10.0 或当时重新审计的正式版
  -> 一个没有 links 的平面 Skill
  -> 一个受限 reference 读取替代方案
```

**必须验证**：

- filesystem、classpath JAR、Spring Boot fat JAR；
- 多个 Skill 包和同名冲突；
- OpenAI-compatible、Anthropic/MiniMax 等实际目标 Provider 的 ToolCallback 参数；
- Skill description 命中率、错误命中率和 Token 消耗；
- references 的安全边界；
- 无社区库时的一键回滚和当前生产链路不受影响。

**禁止条件**：

- 没有把 `allowed-tools`/`model` 误当作权限或路由；
- 没有用通用 Shell/FileSystem 工具替代受限业务 API 边界；
- 没有将 PoC 结果直接改写为当前项目已经切换。

## 5. 文件变更地图

实施时按阶段拆分提交，推荐顺序如下：

| 阶段 | 主要文件 | 结果 |
|---|---|---|
| P0-A | `Skill.java`、`SkillRegistry.java`、Skill Markdown、Registry tests | frontmatter 和注册契约 |
| P0-B | `SkillReferenceReader`、`SkillTools`、`SkillCoreTools`、reference tests | 安全 Level 3 |
| P0-C | `SkillRegistry`、`SpringAIAgent`、`AgUiController`、frontend hook、API tests | 统一 API 校验 |
| P1-A | `src/test/java/com/example/demo/agent/` | 离线确定性测试基座 |
| P1-B | context/state classes、Agent/AG-UI tests | 请求/运行隔离 |
| P1-C | source/provider classes、JAR fixtures | 可复用资源包 |
| P2 | `SkillsAdvisor`、prompt templates、metrics/logging、stable docs | 渐进披露、发现和观测 |
| P3 | 独立 PoC 目录或分支、单独 Maven 配置 | Spring AI 2.x 评估 |

不要在同一个提交中同时升级 Spring AI、替换 Skill 格式、重写 AG-UI 工具执行和
改变前端确认流程。

## 6. 验证矩阵

### 每个阶段都必须运行

```bash
git diff --check
mvn -DskipTests clean package
```

### Skills 离线测试

```bash
mvn -Dtest='*Skill*Test,*Api*Test' test
```

如果 Maven Surefire 对 glob 解析行为不同，使用实际测试类名替代，并将命令记录在
`docs/HARNESS.md`。测试不得要求真实模型 API。

### 前端和 AG-UI

```bash
cd frontend
npm run build
```

具备服务和凭证后再运行 `test-agui-jwt-full.sh`、`test-jwt-get.sh`、前端 E2E；先区分
环境依赖失败和代码失败。

### 资源包与 release 审计

```bash
git submodule status
git -C spring-ai-agent-utils describe --tags --exact-match HEAD
git -C spring-ai-agent-utils status --short --branch
```

社区子模块发生更新时，重新执行 [审计报告的复核命令](../spring-ai-agent-utils-audit.md#如何复核)
并更新报告，而不是只更新 gitlink。

## 7. 风险、默认值与可逆边界

| 问题 | 推荐默认 | 理由 | 可逆边界 |
|---|---|---|---|
| 是否现在迁移社区库 | 否 | 主版本、格式和业务边界不兼容 | 独立 PoC 可随时删除 |
| 是否使用完整 YAML parser | 使用现有 Jackson YAML | 已在主项目中，减少依赖 | 通过 parser adapter 替换 |
| unknown frontmatter 字段 | 保留、忽略执行语义 | 兼容 metadata，避免静默丢失 | 未来版本可收紧 schema |
| duplicate Skill name | 启动失败 | 防止顺序覆盖和不可诊断行为 | 可增加显式优先级 |
| 未索引 URL | 拒绝 | 降低模型猜错或 SSRF 风险 | 先日志模式，再强拒绝 |
| reference 大小 | 读取上限与返回上限分离 | 防止大文件耗尽资源 | 配置可调整 |
| JAR source | 先支持、后默认 | 先吸收测试收益，控制发布风险 | provider 可禁用 |
| Skill state | 显式 turn context | 并发隔离优先于共享字段便利 | 可保留 facade |
| Spring AI 2.x | 独立 PoC | 避免生产链路被升级阻断 | PoC 失败不回滚生产 |

## 8. 恢复执行指南

任务中断后，按如下顺序继续：

1. 运行 `git status --short --branch` 和 `git submodule status`；
2. 阅读本文第 2、4、5、6 节；
3. 重新查看对应阶段的事实来源文件，不假定行号仍然有效；
4. 用 plan 工具恢复阶段状态；
5. 先补回归测试，再实现改动；
6. 每完成一个阶段，更新本文状态、验收结果和风险；
7. 任何规划事实变化都触发三轮连续无修改检查。

## 9. 当前待办排序

| 优先级 | 待办 | 依赖 | 完成标志 |
|---|---|---|---|
| 优先级 | 待办 | 依赖 | 完成标志 | 状态 |
|---|---|---|---|---|
| P0 | frontmatter parser/validator | 无 | 解析和错误测试全绿 | 已完成 |
| P0 | reference reader 安全 | SkillRegistry name lookup | traversal/size tests 全绿 | 已完成 |
| P0 | API index/URL 统一 | 先稳定索引模型 | Java/浏览器语义一致 | 已完成 |
| P1 | deterministic tests | P0 契约 | 不依赖 LLM 可运行 | 已完成最小基线，待扩展 |
| P1 | 普通 Agent turn state isolation | 测试基座 | `ToolContext`/`SkillLoadSession` 和工具回合证据 | 已完成；AG-UI 独立状态仍待后续 |
| P1 | source/provider abstraction | P0 registry | classpath/filesystem/JAR fixture | 待实施 |
| P2 | Level 1/2/3 观测与排序 | P1 tests | prompt 和耗时可观测 | 待实施 |
| P2 | 文档发现链维护 | 当前文档入口 | 从 README/AGENTS 可到达 | 本次已补齐入口，持续维护 |
| P3 | Spring AI 2.x PoC | 独立升级条件 | PoC 报告决定是否继续 | 待触发 |
