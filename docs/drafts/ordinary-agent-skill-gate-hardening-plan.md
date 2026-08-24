# 普通 Agent Skill 渐进式披露后端门禁加固规划

> **状态**: 已实施
> **目的**: 将普通 `AgentService` 链路中“必须先加载 Skill，再读取 reference 或调用业务 API”的要求，从提示词约定提升为后端可执行、可测试、可教学的运行时契约。
> **最后核对**: 2026-08-24
> **范围**: 普通 Agent 后端、运行时 Skill 资源契约、确定性集成测试、学习文档
> **明确排除**: `ag-ui-4j/`、`src/main/java/com/agui/`、`SkillCoreTools`、`AgUiConfig`、CopilotKit、AG-UI SSE

## 1. 背景与问题定义

当前项目用三层渐进式披露组织运行时 Skills：

```text
SkillsAdvisor 注入 Level 1 目录
  -> SkillTools.loadSkill(name) 返回 Level 2 SKILL.md 正文
  -> SkillTools.readSkillReference(name, path) 按需返回 Level 3 reference
  -> SkillTools.httpRequest / buildHttpRequest 调用或准备 Skill 描述的业务 API
```

实施前源码中 `SkillsAdvisor` 和 prompt 模板已经要求模型先调用 `loadSkill`，但普通链路
的 `SkillTools` 曾允许在当前 `SkillLoadSession` 没有记录已加载 Skill 时：

- 直接调用已登记的 GET API；
- 直接构建已登记的写操作确认元数据；
- 直接读取某个已登记 Skill 的 Level 3 reference。

这会造成两个问题：

1. **运行时安全与一致性**：模型或调用方可以跳过 Level 2 指令，仍然访问 API。API
   index 只验证“这个 URL 是否登记”，没有验证“该 URL 所属 Skill 是否已披露”。
2. **Demo 教育信号不完整**：读者从 prompt 看到的是“先 loadSkill”，但后端测试和工具
   实现没有把它作为硬门禁，容易误以为渐进式披露只是提示词技巧，而不是应用层协议。

本批不改变 API URL allowlist、写操作确认、认证透传或 AG-UI 的浏览器工具边界；只补齐
普通 Agent 的 Skill 加载状态门禁。

## 2. 当前实现上下文

### 2.1 关键源码

| 文件 | 当前职责 | 本批关系 |
|---|---|---|
| `src/main/java/com/example/demo/agent/SkillRegistry.java` | 扫描 Skill、解析 frontmatter、构建 API index、校验 URL | 根据 method/path 找到 API 所属 Skill |
| `src/main/java/com/example/demo/agent/SkillLoadSession.java` | 当前普通 ChatClient 调用的已加载 Skill 集合 | 作为门禁的请求级状态源 |
| `src/main/java/com/example/demo/agent/SkillTools.java` | 普通链路的 `loadSkill`、Level 3 reference、GET、写操作请求构建 | 加入门禁 |
| `src/main/java/com/example/demo/service/AgentService.java` | 为每个普通请求创建 `SkillLoadSession` 并传入 `ToolContext` | 保证门禁状态按请求隔离 |
| `src/main/resources/prompts/skills-advisor/backend-mode-rules.template` | 普通链路的模型行为规则 | 保留并补充“后端仍会强制校验”的教学说明 |
| `src/main/resources/skills/` | Skill frontmatter、Level 2 正文、Level 3 references | 不改变现有资源格式 |

### 2.2 当前状态隔离

`AgentService.toolContext()` 每次调用创建新的 `SkillLoadSession`。因此门禁必须只读取该
请求上下文，不使用 Spring singleton、静态集合或跨请求缓存。`SkillCoreTools` 的
`CopyOnWriteArrayList` 是 AG-UI 专用状态，本批不修改，也不能让普通链路复用它。

### 2.3 当前 API 所属关系

`SkillRegistry` 已经从 API index 得到 method/path 对应的 `ApiIndexEntry`，其
`skillName` 是 canonical Skill 名称：

- `GET /api/products` -> `search-products`
- `GET /api/products/{id}` -> `get-product-detail`
- `GET /api/products/cart` -> `view-cart`
- `POST /api/products/cart` -> `add-to-cart`
- `POST /api/products/checkout` -> `checkout`
- PetStore 19 个操作 -> `swagger-petstore-openapi-3-0`

`validateApiRequest()`/`validateResolvedApiRequest()` 继续负责 URL 是否属于登记的 API；
本批在其成功之后，再校验对应 `skillName` 是否已在当前 `SkillLoadSession` 中出现。

## 3. 推荐设计

### 3.1 门禁规则

普通 `SkillTools` 采用以下顺序：

1. 校验工具参数和 HTTP method；
2. 校验 URL 是否为相对路径、是否通过 API index；
3. 对 API 工具，根据已解析的 `ApiIndexEntry.skillName` 检查 Skill 是否已加载；
4. 只有门禁通过后才构造或发送 HTTP 请求；
5. 对 reference 工具，根据传入 `skillName` 检查 Skill 是否已加载；
6. 只有门禁通过后才调用现有受限 `SkillReferenceReader`。

未加载时返回稳定、可被模型理解的中文错误：

```text
工具调用被拒绝：当前请求尚未加载技能 `search-products`。
请先调用 loadSkill("search-products") 获取完整操作指令。
```

缺少 `ToolContext` 或缺少 `SkillLoadSession` 时返回上下文错误，而不是退化为允许执行。
这使直接手工调用普通工具也不会绕过渐进式披露。

### 3.2 对不同工具的处理

| 工具 | 门禁 | 说明 |
|---|---|---|
| `loadSkill` | 无需已加载；需要有效请求级 `SkillLoadSession` | 这是进入 Level 2 的入口 |
| `readSkillReference` | 需要该 `skillName` 已加载 | 只改普通 `SkillTools`，AG-UI `SkillCoreTools` 保持原有协议 |
| `httpRequest` | 需要 API 所属 Skill 已加载 | 仍只允许 GET；继续透传已验证 token |
| `buildHttpRequest` | 需要 API 所属 Skill 已加载 | 仍只登记一个经 API index 校验的写操作，不发送请求 |

为保持 Spring AI 工具调用兼容，普通 `readSkillReference` 增加 `ToolContext` 参数；与
现有 `loadSkill`、`httpRequest`、`buildHttpRequest` 一样，Spring AI 将其视为调用上下文
参数而不是模型 JSON 参数。AG-UI 使用独立的 `SkillCoreTools.readSkillReference`，不受
这个签名变化影响。

### 3.3 错误优先级

- URL 非法或未登记：先返回现有 URL/API index 错误；当前实现会在“未登记 API”错误中
  列出可用 method/path 及其 `loadSkill(...)` 提示，本批不改变这项已有诊断行为；
- URL 已登记但 Skill 未加载：返回 Skill 门禁错误；
- API 已登记且 Skill 已加载：保持现有 HTTP/确认行为；
- reference Skill 不存在：先保持 `SkillReferenceReader` 的“技能不存在”错误，不把
  未知名称误报成“尚未加载”；已知 Skill 存在但未加载时才返回 Skill 门禁错误；
- reference Skill 存在但未加载：返回 Skill 门禁错误；
- 缺少上下文：返回“缺少当前请求的 Skill 会话上下文”，不得执行。

这样可以把“输入非法”“资源不存在”“尚未披露”和“可执行”四类状态分开，方便读者从
测试理解工具协议。

## 4. 实施步骤

### 阶段 A：先写验收测试（已完成）

在修改生产代码前，一次性补充：

1. `SkillToolsTest`
   - 先更新所有现有成功路径：在调用 `httpRequest`、`buildHttpRequest` 或普通
     `readSkillReference` 前，使用同一个 `ToolContext` 显式调用对应 `loadSkill`；
     这样既保持测试意图，也把每个成功样例写成完整的渐进式披露回路；
   - 未加载 Skill 时，GET 不发出下游请求并返回门禁错误；
   - 加载对应 Skill 后，GET 继续成功并透传 token；
   - 未加载 Skill 时，`buildHttpRequest` 不登记确认状态；
   - 加载对应 Skill 后，写操作元数据和原有单请求确认边界不变；
   - 未加载 Skill 时，普通 reference 读取被拒绝；
   - 加载后 reference 读取成功；
   - 只加载其他 Skill 时，业务 API 和 reference 仍按所属 Skill 拒绝；
   - 缺少 `ToolContext`/`SkillLoadSession` 时拒绝。
2. `BackendApiIntegrationTest`
   - 现有 scripted ChatModel 的正常路径仍必须经历
     `loadSkill -> httpRequest`；
   - 增加一个“跳过 loadSkill 的 scripted tool call”场景，证明 API 没有被调用、
     模型收到门禁错误后可以继续纠正或最终返回错误；
   - 保留现有真实 Spring AI Tool Calling 回路断言。
3. 现有 `SkillReferenceReaderTest`
   - 更新普通工具调用以传入 `ToolContext`；
   - 明确 ordinary `SkillTools` 和 AG-UI `SkillCoreTools` 仍共享底层 reader，
     但只普通工具受请求级加载门禁约束。

测试必须先失败，才能证明测试覆盖了新行为；不把真实 LLM 作为离线契约的前置条件。

### 阶段 B：实现最小生产改动（已完成）

实际修改范围为：

- `SkillTools.java`：增加请求级 Skill 门禁和 `readSkillReference` 的 `ToolContext`；
- 必要时只增加一个私有门禁辅助方法，不引入新的全局状态或抽象层；
- 不修改 `SkillRegistry` 的 API index 算法；
- 不修改 `SkillCoreTools`、AG-UI、CopilotKit 和浏览器代码。

门禁辅助方法应使用 `SkillRegistry.findApiEntry(resolvedUrl, method)` 获取 canonical
Skill，避免从 URL 字符串猜测 Skill 名称。该方法只在 API index 校验成功后调用，因此
不会产生第二套路由规则。

### 阶段 C：文档和可发现性（已完成）

更新以下稳定文档，使读者容易发现“渐进式披露是后端协议，不只是 prompt”：

- `docs/knowledge-and-skills.md`：增加普通链路的强制门禁、错误示例和测试入口；
- `docs/learning-path.md`：在 Tool Calling/Skills 教学步骤加入“跳过 loadSkill 会被拒绝”
  的可验证实验；
- `docs/rest-api.md` 或 `docs/HARNESS.md`：记录该门禁属于工具边界，不是 HTTP 端点认证，
  并指向确定性测试；
- `AGENTS.md`：只在现有普通 Agent 工具边界说明中补一条当前事实，不复制长文。

基本集成验证、真实 LLM/传统页面验证和三轮实现审查均已完成；本文件记录实际文件、
命令、测试结果和审查摘要。

## 5. 验收标准

### 后端硬门槛

```bash
mvn clean compile test-compile
mvn -Dtest='SkillToolsTest,SkillReferenceReaderTest,SkillRegistryTest,BackendApiIntegrationTest' test
mvn test
```

预期：所有相关测试和全量默认测试通过；默认 Maven 测试不访问真实 LLM。

实际结果（2026-08-24）：

- `mvn clean compile test-compile`：通过；存在仓库既有的 deprecated/unchecked 编译警告，
  无编译错误。
- `mvn -Dtest='SkillToolsTest,SkillReferenceReaderTest,SkillRegistryTest,BackendApiIntegrationTest' test`：
  40/40 通过。
- `mvn test`：60/60 通过；默认 Surefire 排除 `live-llm,container`。
- `git diff --check`：通过。

### 当前实施结果

已完成的生产与测试改动：

- `src/main/java/com/example/demo/agent/SkillTools.java`
  - `httpRequest` 和 `buildHttpRequest` 在 API index 校验后检查 API 所属 Skill；
  - 普通 `readSkillReference` 增加 `ToolContext`，检查 reference 所属 Skill；
  - 缺少请求上下文时拒绝，不退化放行；
  - 未加载时不发送下游 GET，也不登记待确认写操作。
- `src/test/java/com/example/demo/agent/SkillToolsTest.java`
  - 覆盖请求上下文隔离、缺少上下文、未加载、加载错误 Skill、参数边界和下游请求；
- `src/test/java/com/example/demo/agent/SkillReferenceReaderTest.java`
  - 覆盖普通 reference 门禁、成功读取、非法 Skill 名称以及与 AG-UI reader 的边界；
- `src/test/java/com/example/demo/BackendApiIntegrationTest.java`
  - 覆盖正常 `loadSkill -> httpRequest` 回路和跳过加载时的后端拒绝；
- 稳定文档和普通 Agent prompt 已说明：渐进式披露是请求级后端契约，不只是提示词约定。

### 真实 LLM 补充验证

用户已授权使用根目录 `.env` 中的本地凭证。Mock/确定性门槛通过后，选择最小普通页面
真实链路：

1. 使用 `./dev.sh --backend-only` 启动当前 main 工作区，记录 profile、端口和日志目录；
2. 用传统页面 Playwright 或等价 HTTP/浏览器路径发送一次只读商品查询；
3. 观察日志确认出现 `loadSkill` 后才出现 `httpRequest`，并确认最终页面显示真实商品；
4. 关闭服务并确认 8080 端口释放。

本批不要求 AG-UI、CopilotKit、RAG、多模态或 PostgreSQL 专项作为行为证明；若 dev profile
启动依赖 PostgreSQL/Embedding，必须区分环境阻塞与代码失败，并可用 H2/local profile
完成普通 Skill 真实模型烟测。

实际结果（2026-08-24）：

- 根目录 `.env` 已获授权使用；未打印或提交任何凭证。
- `RUN_LIVE_LLM_TESTS=true mvn -Dtest.excluded-groups=container -Dtest=OpenAiCompatibleApiLiveTest test`：
  1/1 通过。首次使用错误属性名得到 0/0，已根据 `pom.xml` 的
  `test.excluded-groups` 配置纠正并重新执行；0/0 未计为通过。
- `./dev.sh --backend-only`：使用 `postgresql` profile 启动成功，PostgreSQL、OpenAI-compatible
  `grok-4.5` 和 SiliconFlow embedding 配置均成功初始化，`/api/products` 健康。
- `(cd frontend && npm run test:e2e:traditional)`：通过。嵌入式传统页面完成登录，
  真实 `/api/chat/text` 返回 200，DOM 显示真实商品结果。
- `(cd frontend && npm run test:e2e:traditional:mutation)`：通过。真实模型完成
  `loadSkill -> buildHttpRequest`，取消没有业务 POST，确认后加入购物车并调用结果解释，
  最后通过受保护 API 清理购物车。
- `./dev.sh --stop` 后 8080 端口已释放；AG-UI/CopilotKit 端点未纳入本批行为证明。

### 教学验收

读者应能从以下路径理解并复现：

```text
docs/learning-path.md
  -> SkillTools
  -> SkillLoadSession
  -> SkillToolsTest / BackendApiIntegrationTest
  -> 真实传统页面普通 Agent 流程
```

## 6. 风险、默认值与回滚

### 推荐默认值

- **只在普通 `SkillTools` 加门禁**：因为普通链路拥有请求级 `ToolContext`；AG-UI
  使用独立状态和工具回合，混改会扩大风险。
- **缺少上下文时拒绝**：渐进式披露是本批要加固的安全/教学边界，不能为了兼容旧手工
  调用而静默放行。
- **不要求 Skill graph 的传递加载**：加载 `search-products` 不自动加载
  `get-product-detail`；links 仍只是下一步提示，模型需要按实际动作显式加载目标 Skill。
  该边界最容易解释，也与当前 token 优化目标一致。
- **不把门禁错误改成异常**：工具返回可读错误，让模型有机会纠正；非法 Skill 资源仍在
  启动期通过 `SkillDefinitionException` 失败。

### 可逆边界

如果现有 provider 对带 `ToolContext` 的 `readSkillReference` schema 产生兼容问题，可
回滚该方法的门禁签名，保留 GET/写操作门禁；但在确认兼容前不得宣称 Level 3 也已被后端
强制保护。生产改动集中在一个工具类，回滚只需恢复该类并同步测试。

### 不在本批处理的事项

- Skill JAR/provider 抽象和社区库替换；
- Spring AI 2.x Tool Search PoC；
- AG-UI singleton 状态隔离；
- URL 路由冲突检测、控制器自动比对；
- 真实 LLM 成本/Token 统计。

这些可以在后续批次单独规划，避免把普通链路门禁和平台迁移混成一次改造。

## 7. 中断恢复说明

若任务在实施中断，按以下顺序继续：

1. 阅读本文件“当前实现上下文”和“验收标准”；
2. 查看 `git status`，不要覆盖用户已有改动；
3. 先运行 `mvn -Dtest=SkillToolsTest,SkillReferenceReaderTest test`，确认测试当前状态；
4. 再按阶段 A/B/C 逐项完成；
5. 代码改动后先过基本集成硬门槛，再进行三轮限定实现审查；
6. 真实 LLM 只在确定性测试通过后运行，且不提交 `.env` 或日志密钥。

## 8. 规划审查记录

规划文档完成后，必须连续三轮无修改审查。每轮检查范围和结果记录在这里；若发现事实、
逻辑、可执行性、安全、测试或可发现性问题，立即修改本文档并将计数器归零。

| 轮次 | 时间 | 检查范围 | 发现与处理 | 结果 |
|---|---|---|---|---|
| 1 | 2026-08-24（实施前） | 源码、测试、Skill 资源、普通 Agent 调用链 | 修正未登记 URL 错误提示的事实描述；计数器重置后继续 | 通过 |
| 2 | 2026-08-24（实施前） | 依赖、工具签名、错误边界、回滚与验证 | 补充既有成功测试必须显式 `loadSkill` 的迁移要求 | 通过 |
| 3 | 2026-08-24（实施前） | 文档导航、教学闭环、实施范围与排除项 | 交叉核对 Spring AI 工具上下文签名、稳定文档入口和 AG-UI 排除边界；无新问题 | 连续三轮无修改通过 |

## 9. 实施后收敛记录

本节记录基本集成验证之后的三轮限定范围实现审查。检查范围固定为本批修改的
`SkillTools`、三组确定性测试、普通 Agent prompt、稳定文档和交付状态；不扩展到
CopilotKit/AG-UI 实现或无关可选优化。若发现影响正确性、安全、兼容性、成本或数据一致性
的问题，必须修复并将计数器重置为 0。

| 轮次 | 时间 | 检查范围 | 发现与处理 | 结果 |
|---|---|---|---|---|
| 1 | 2026-08-24 14:43 | `SkillTools` 执行顺序、API 所属匹配、`ToolContext` 隔离、Spring AI 工具签名 | 无问题；确认门禁位于 URL/API index 校验之后、下游执行之前 | 无修改通过 |
| 2 | 2026-08-24 14:45 | reference/URL/确认边界、错误优先级、请求级状态和普通/AG-UI 边界 | 无问题；确认路径安全、未加载不执行/不登记、状态不跨请求共享 | 无修改通过 |
| 3 | 2026-08-24 14:47 | 测试覆盖、文档可发现性、验证命令、提交范围和子模块状态 | 发现规划记录尚未收束；仅更新本规划文档的实施记录，不改代码；随后复核无新问题 | 文档收尾后通过 |

连续三轮实现审查均未发现需要修改生产代码的问题；文档收尾不改变实现审查结论。
