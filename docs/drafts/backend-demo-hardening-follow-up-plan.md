# 后端与 Demo 教育性后续加固规划

> **状态**: 已实施
> **目的**: 在上一轮后端基础加固已经完成的基础上，继续选择一批高收益、低风险、
> 可自动验收的改进，提升普通 Agent 后端链路的可信度、运行时 Skill 的契约完整性和
> Spring AI Demo 的学习路径。
> **最后核对**: 2026-08-24
> **前置规划**: [后端与 Demo 全面加固规划](backend-demo-hardening-plan.md)
> **长期规则**: 本规划遵循根目录 [AGENTS.md 的规划、验收和三轮收敛原则](../../AGENTS.md)

## 1. 决策摘要

本轮直接实施以下四个相互关联的改进批次：

1. **运行时 Skill 契约完整性**：启动时校验 `links` 指向已注册 Skill，拒绝自引用和
   重复关联；增加测试证明 `/api/agui/skills/api-index` 中的每个端点都有对应的
   Spring MVC handler。
2. **普通 Agent 写操作教育闭环**：增加确定性的 Spring Context 集成测试，证明
   `loadSkill -> buildHttpRequest -> 返回确认元数据`，随后用同一认证用户通过真实 HTTP
   调用完成 `add-to-cart -> view-cart -> checkout`，并确认结算后购物车清空。
3. **普通文本 SSE 契约**：增加 Controller 层自动化测试，锁定事件 JSON、连续 token
   传输和 `[DONE]` 结束标记；不把 AG-UI SSE 纳入本轮。
4. **Demo 学习入口和文档契约**：修正 `view-cart` Skill 与真实响应模型的漂移，新增
   一条从 REST 到 ChatClient、Tool Calling、Skills、记忆/RAG、SSE 和 AG-UI 的学习主线，
   并把该入口加入 README、`docs/README.md`、`AGENTS.md` 和草稿索引。

本轮不改变普通 Agent 的工具名称、API 路径、认证格式、Spring AI 版本或 AG-UI/CopilotKit
运行时行为。生产代码改动只限于 Skill link 启动校验；其余主要由集成测试和稳定教学文档
构成。

## 2. 当前事实与问题

### 2.1 已有基础

上一轮已经完成并推送：

- `SkillRegistry` 的 YAML frontmatter 解析、API index、URL 校验和 reference 边界；
- 普通 `AgentService` 的请求级 `SkillLoadSession` 和 backend-mode prompt；
- `SkillTools` 的 GET allowlist、写操作元数据、参数/响应上限和认证 TokenContext；
- 登录、商品 API、会话命名空间、知识库稳定 ID、向量库分离和默认离线测试；
- `dev.sh`、传统页面 Mock/真实 Playwright、前端构建和真实普通页面闭环。

这些结论的详细证据仍以 [前置规划](backend-demo-hardening-plan.md)、
[验证手册](../HARNESS.md) 和源码为准。

### 2.2 本轮确认的缺口

| 缺口 | 影响 | 当前边界 |
|---|---|---|
| Skill `links` 只校验名称格式，不校验目标是否存在、是否自引用或重复 | 模型收到悬空或重复的下一步提示，读者难以相信 Skill 图是可执行的 | `SkillRegistry` |
| API index 有单元测试，但没有证明每个索引条目对应真实 Controller mapping | Skill 文档、索引和 Spring MVC 端点可能漂移，模型会得到“已注册但不可调用”的路径 | `SkillRegistry` + `BackendApiIntegrationTest` |
| 普通 Agent 只验证了 Skill 加载和 GET 工具回合 | 没有展示 Spring AI Tool Calling 如何停在写操作确认边界，也没有验证确认后的业务闭环 | 普通 `/api/chat/text` + 商品 API |
| 普通 `/api/chat/stream` 的 SSE 输出没有稳定后端测试 | 事件格式或 `[DONE]` 结束标记漂移时，前端只能在运行时发现 | `ChatController` |
| `view-cart/SKILL.md` 的示例字段与 `ProductService` 实际返回的 `Product` 对象不一致 | Skill 作为模型 API 指令不可信，直接损害 Demo 教学效果 | `src/main/resources/skills/view-cart/SKILL.md` |
| 读者需要从多个文档拼接 Spring AI 学习顺序 | 能力很多但主线不明显，容易把项目自定义层误认为 Spring AI 内置能力 | README、`docs/README.md`、稳定文档导航 |

## 3. 优先级与实施边界

### P0：本轮必须实施

#### P0-A：Skill links 图完整性

**修改边界**

- `src/main/java/com/example/demo/agent/SkillRegistry.java`
- `src/test/java/com/example/demo/agent/SkillRegistryTest.java`

**规则**

- 所有 Skill 完成注册后，再统一校验 `links`；
- link 目标必须是已注册 Skill；
- 不允许 Skill 指向自身；
- 同一个 Skill 内不允许重复 link 名称；
- 错误必须包含 Skill 名称和来源，启动失败而不是静默丢弃。

**默认理由**

运行时 Skill 是静态资源，启动期 fail-fast 比让模型在运行中反复发现悬空链接更容易
诊断，也不会改变已注册 Skill 的运行时格式。该校验只作用于 metadata graph，不改变
技能正文、API index 或工具调用协议。

**回滚边界**

如果未来需要跨包引用尚未加载的 Skill，应先引入明确的 provider/namespace 机制；本轮
不通过放宽校验来兼容未知来源。

#### P0-B：Skill API index 与 Spring MVC mapping 契约测试

**修改边界**

- `src/test/java/com/example/demo/BackendApiIntegrationTest.java`
- 必要时仅调整测试辅助方法，不修改 Controller mapping。

**验证规则**

- 从真实 Spring ApplicationContext 获取 `RequestMappingHandlerMapping`；
- 对 `SkillRegistry.getApiIndex()` 的每个条目检查 HTTP method 和路径模板都存在真实
  handler；
- 额外通过 HTTP 读取 `/api/agui/skills/api-index`，检查关键字段和分层 reference
  信息仍然可序列化；
- 测试使用当前真实 Controller、SkillRegistry、Security 配置和端口，不 mock
  Controller 或 API index。

**默认理由**

这是一条低成本的跨模块契约测试：它不会把 OpenAPI parser 引入生产，也不会要求模型或
数据库，却能直接防止 Skill 文档与业务端点漂移。

**回滚边界**

如果某个 Petstore endpoint 由通配 mapping 承载，测试只接受真实 Spring path pattern
匹配，不通过硬编码白名单掩盖缺失 mapping。

#### P0-C：普通 Agent 写操作和商品 API 完整闭环

**修改边界**

- `src/test/java/com/example/demo/BackendApiIntegrationTest.java`
- `src/main/resources/skills/view-cart/SKILL.md`

**验证场景**

1. 登录获取 Demo bearer token；
2. 带认证请求进入 `/api/chat/text`；
3. Scripted `ChatModel` 依次要求 `loadSkill("add-to-cart")` 和
   `buildHttpRequest("POST", "/api/products/cart", ...)`；
4. 断言模型收到的是经过生产代码校验后的确认 JSON，而不是已执行的写请求；
5. 测试模拟传统页面“用户确认”，用同一 token 真实调用 POST 加入购物车；
6. GET 购物车检查商品数量；
7. POST checkout 检查金额/数量；
8. 再次 GET 购物车确认已清空。

**默认理由**

写操作确认本来就是该 Demo 区分“模型提议”和“用户授权执行”的关键教学点。测试不把
用户确认伪装成模型行为，也不引入浏览器依赖；真实页面确认由已有 Playwright 闭环覆盖，
后端测试负责锁定 API 和工具协议。

**回滚边界**

不把购物车升级成持久化订单系统，不修改 ProductService 的 Demo 数据模型；若未来引入
订单快照，应另开规划并保留本场景作为 API 契约回归。

### P1：本轮一并实施

#### P1-A：普通文本 SSE 事件契约

**修改边界**

- 新增 `src/test/java/com/example/demo/controller/ChatControllerTest.java`
- `ChatController` 仅在测试暴露实际缺陷时做最小修复

**验证**

- `AgentService.streamChat()` 返回两个确定性 token；
- POST `/api/chat/stream`；
- 断言 HTTP 200、SSE data 中的 OpenAI-compatible `choices[0].delta.content`；
- 断言所有 token 按顺序出现，最后出现独立 `[DONE]`；
- 不用截图，不调用真实模型，不覆盖 `/api/agui`。

#### P1-B：运行时 Skill 文档与学习路径

**修改边界**

- 修正 `src/main/resources/skills/view-cart/SKILL.md` 的返回结构；
- 新增 `docs/learning-path.md`；
- 更新 `README.md`、`docs/README.md`、`AGENTS.md`、`docs/drafts/README.md`、必要时
  `docs/HARNESS.md` 的近距离导航；
- 不复制完整架构/配置/API 文档，使用源码和稳定文档链接。

**学习主线**

```text
ProductController/ProductService
  -> ChatController/AgentService
  -> ChatClient + ToolCallAdvisor
  -> SkillsAdvisor + SkillRegistry
  -> SkillTools.loadSkill/httpRequest/buildHttpRequest
  -> JDBC Chat Memory
  -> 可选 RAG/VectorStore
  -> 普通 SSE/多模态
  -> AG-UI/CopilotKit（独立高级链路）
```

每一步都要链接到实际源码、一个确定性测试和必要的运行命令，并明确区分：

- Spring AI 提供的抽象；
- 当前项目自定义的 Skills/API index/确认协议；
- 需要外部模型、Embedding、PostgreSQL 或浏览器的能力。

## 4. 明确不纳入本轮的建议

以下事项确实仍有价值，但不纳入本轮实施，以保持范围收敛：

| 后续项 | 暂不实施理由 |
|---|---|
| `SkillCoreTools` 的 AG-UI singleton state 并发隔离 | 属于 AG-UI/CopilotKit run context，需要独立协议和 SSE 生命周期设计 |
| ProductService 的数量模型、库存扣减、订单持久化 | 会改变 Demo 业务模型和已有 API，不是本轮 Skills/教育闭环的必要条件 |
| `JsonArgToolCallback` 的泛型参数反序列化 | 只影响 AG-UI 适配边界，需结合上游工具 schema 单独验证 |
| Skill source/provider/JAR 资源抽象 | 仍是社区库借鉴项，先以现有 classpath 资源和契约测试巩固生产链路 |
| Spring AI 2.x 或社区 `SkillsTool` 替换 | 仍按审计报告保留为隔离 PoC，不进入生产链路 |
| 多模态、RAG 真实 LLM 全量回归 | 与本轮普通 Agent/教育性改动没有直接增量证据 |

## 5. 验收矩阵

| 层级 | 证据 | 通过标准 |
|---|---|---|
| Skill 单测 | `SkillRegistryTest` | 悬空、自引用、重复 link 拒绝；当前资源图加载成功 |
| 后端契约集成 | `BackendApiIntegrationTest` | API index 每项都有 Spring MVC mapping；关键 index JSON 可读 |
| Agent 工具回合 | `BackendApiIntegrationTest` | `loadSkill -> buildHttpRequest` 只产生确认元数据，不直接 POST |
| 业务闭环 | `BackendApiIntegrationTest` | token 用户完成 add/cart/checkout，结算后购物车清空 |
| SSE | `ChatControllerTest` | token data 顺序正确，最后为 `[DONE]` |
| 文档契约 | `view-cart/SKILL.md`、`docs/learning-path.md` | Skill 字段与源码一致，学习入口可从稳定文档和草稿索引发现 |
| 基本硬门槛 | Maven、`git diff --check` | 编译、相关测试和文档检查通过 |

基本命令：

```bash
mvn clean compile test-compile
mvn -Dtest='SkillRegistryTest,SkillReferenceReaderTest,SkillToolsTest,BackendApiIntegrationTest,ChatControllerTest' test
git diff --check
```

本轮不要求新增真实 LLM 调用；已有真实传统页面和 provider 烟测继续作为上一轮证据。
如果实现过程中发现模型协议或真实服务行为确实受影响，先完成 Mock/确定性门槛，再选择
一个最小真实普通页面场景复验。

## 6. 实施顺序

1. 先完成本文和三轮连续无修改的规划审查；
2. 先新增/扩展确定性验收测试，使测试覆盖目标行为；
3. 实施 Skill link fail-fast 校验；
4. 修正 `view-cart` Skill 和新增学习路径导航；
5. 运行后端硬门槛与相关测试；
6. 如测试发现影响正确性、兼容性、安全、成本或数据一致性的缺陷，修复后重新从
   基本硬门槛开始，且实现审查计数归零；
7. 通过硬门槛后进行三轮固定范围代码审查；
8. 更新本文实施记录、稳定文档和测试证据，确认提交范围干净。

## 7. 回滚与中断恢复

- link 校验可单独回滚 `SkillRegistry` 的启动期验证，不改变 Skill 文件和工具接口；
- 测试和学习文档可独立回滚，不影响运行时；
- `view-cart` 只修正文档示例，不改变 API JSON；
- 如果任务中断，从本文“实施顺序”第一个未完成项继续，先执行
  `git status --short`，再核对本文状态和源码，不重新创建第二套计划。

## 8. 实施记录

### 8.1 实际修改

本轮按本文边界完成了以下修改：

- `src/main/java/com/example/demo/agent/SkillRegistry.java`
  增加 Skill `links` 的启动期 fail-fast 校验，拒绝悬空目标、自引用和重复关联。
- `src/test/java/com/example/demo/agent/SkillRegistryTest.java`
  增加上述三类非法 Skill 图的确定性测试。
- `src/test/java/com/example/demo/BackendApiIntegrationTest.java`
  增加 API index 与 Spring MVC mapping 契约测试，以及普通 Agent
  `loadSkill -> buildHttpRequest -> 用户确认 -> 商品 API -> checkout` 闭环测试。
- `src/test/java/com/example/demo/controller/ChatControllerTest.java`
  新增普通文本 SSE token 顺序、JSON chunk 和 `[DONE]` 契约测试。
- `src/main/resources/skills/view-cart/SKILL.md`
  修正购物车返回结构，使示例与 `ProductService` 的实际 `Product` 数组一致。
- `docs/learning-path.md`
  新增从 REST、ChatClient、Tool Calling、运行时 Skills、记忆/RAG、SSE 到 AG-UI
  的可发现学习主线。
- `README.md`、`AGENTS.md`、`docs/README.md`、`docs/HARNESS.md`、
  `docs/knowledge-and-skills.md`、`docs/drafts/README.md`
  增加学习主线和本规划的近距离导航。

本轮没有修改前端源码、AG-UI/CopilotKit 运行链路或社区库子模块。

### 8.2 验证结果

已通过：

```text
mvn clean compile test-compile
mvn -Dtest='SkillRegistryTest,SkillReferenceReaderTest,SkillToolsTest,BackendApiIntegrationTest,ChatControllerTest' test
mvn test
git diff --check
```

结果：

- 本轮相关测试：23 个全部通过；
- 默认 Maven 测试：33 个全部通过；
- 文档学习主线引用的源码、测试和稳定文档路径均存在；
- 前端 `npm run test:repository`、`npx tsc --noEmit`、`npm run build`、
  `npm run test:skills` 和 `npm run test:e2e:traditional:mock` 均通过；
- 在用户提供有效本地凭证的前提下，使用 `./dev.sh --backend-only` 以
  `postgresql` profile 启动真实服务，确认 PostgreSQL、OpenAI-compatible
  `grok-4.5` 和 SiliconFlow Embedding 配置成功；随后
  `npm run test:e2e:traditional` 通过，真实 `/api/chat/text` 返回 200，嵌入式传统
  页面完成登录并在 DOM 中渲染商品结果；
- 当前工作区未发现 `.env`、密钥、`target/` 或浏览器产物进入改动范围。

### 8.3 实施后三轮收敛审查

以下三轮为硬门槛通过后的固定范围只读审查。每轮均重新按需交叉阅读源码、测试和文档；
没有发现问题，也没有修改代码或测试，因此连续计数达到 3：

| 轮次 | 检查时间 | 检查范围 | 发现与处理 | 结果 |
|---|---|---|---|---|
| 第 1 轮 | 2026-08-24 | SkillRegistry、API index mapping、普通 Agent 工具回合、SSE Controller 契约 | 未发现影响正确性、API 兼容性或事件顺序的问题；无需处理 | 通过 |
| 第 2 轮 | 2026-08-24 | Skill link fail-fast、安全校验、用户确认边界、认证 TokenContext、购物车状态隔离 | 未发现绕过确认、跨用户状态污染或悬空 Skill link 问题；无需处理 | 通过 |
| 第 3 轮 | 2026-08-24 | 测试覆盖矩阵、学习路径入口、稳定文档导航、提交范围和产物检查 | 所有规划项均有测试或文档证据，引用路径存在，`git diff --check` 通过；无需处理 | 通过 |

### 8.4 剩余边界

- 本轮没有重新覆盖 AG-UI/CopilotKit singleton state、AG-UI SSE 或前端运行链路；
  这些仍由既有专项文档和测试承担。
- 本轮新增的是确定性 Mock/集成证据，没有把真实 LLM 当作 Skill 图、API index 或
  确认协议的唯一正确性证明。真实传统页面闭环仍按 [验证手册](../HARNESS.md)
  的 Mock 优先顺序执行。
- ProductService 仍是内存购物车 Demo，不代表生产级库存、订单和并发一致性模型。
