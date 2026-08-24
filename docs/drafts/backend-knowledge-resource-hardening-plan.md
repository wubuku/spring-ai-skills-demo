# 后端知识库与 Skill 资源契约加固规划

> **状态**: 已实施
> **目的**: 在上一批 Skill link、API index、普通 Agent 写操作和 SSE 契约加固之后，
> 继续提升“Markdown 文档如何变成知识”和“Skill 资源如何可靠加载”这两条后端教学主线。
> **最后核对**: 2026-08-24
> **前置基线**: [后端与 Demo 教育性后续加固规划](backend-demo-hardening-follow-up-plan.md)
> **长期规则**: 本规划遵循根目录 [AGENTS.md 的规划、验收和三轮收敛原则](../../AGENTS.md)

## 1. 决策摘要

本批只实施两个相互独立但都属于“资源契约”的改进：

1. **知识库导入确定性与中文编码**：
   `KnowledgeBaseInitializer` 使用 UTF-8 读取 Markdown；对同一轮加载中重复命中的
   normalized source 去重；按 normalized source 排序后再写入 VectorStore，保证多个 glob
   和不同配置顺序不会改变文档顺序。
2. **Skill 资源名称契约**：
   解析 frontmatter 时把合法的 `links.name` 规范化为 trim 后的值；当 Skill 来源路径能
   明确解析出 `skills/<directory>/SKILL.md` 时，要求目录名与 frontmatter `name` 一致，
   避免分层 Skill 的 references 根据错误目录加载。

本批不修改商品业务模型、Controller 路径、普通 Tool 方法签名、Skill API index 算法、
AG-UI/CopilotKit 运行时、Spring AI 版本或社区子模块。实现以生产代码最小修改、确定性
单元测试和近距离教育文档为主。

## 2. 当前事实与问题

### 2.1 知识库当前导入链路

当前实现位于 `src/main/java/com/example/demo/knowledge/KnowledgeBaseInitializer.java`：

```text
ApplicationReadyEvent
  -> loadKnowledgeBase()
  -> 遍历 knowledge-base.paths
  -> ResourcePatternResolver.getResources(pathPattern)
  -> readResource(resource)
  -> VectorStore.add(allDocuments)
```

已确认的当前行为：

- `readResource` 使用 `InputStreamReader(resource.getInputStream())`，没有显式指定字符集；
  在开发机上通常表现为 UTF-8，但不是代码层面的稳定契约。
- 每个资源会根据规范化 source 生成稳定 UUID，并写入 `kind=knowledge`、`source`、
  `originalId` 和 `filename` metadata。
- 同一个 source 如果被多个 glob 或重复配置命中，当前 `allDocuments` 会重复追加；
  现有测试只证明两次独立调用生成相同 ID，没有证明单次加载去重。
- 当前资源返回顺序来自 resolver 和配置列表，代码没有做全局排序；不同 path 配置顺序
  可能改变 VectorStore.add 的输入顺序和日志观察顺序。
- `fail-fast=false` 的默认策略仍应保留：个别资源失败时允许 Demo 启动，但记录明确日志；
  本批不把“启动必须成功”改成默认 fail-fast。

影响：

- 中文保修、退货、配送条款可能在不同默认 charset 环境中被错误解码；
- 同一文档可能重复嵌入，增加成本并污染 Demo 的可观察结果；
- 读者无法从源码中看出知识导入的确定性排序和重复资源语义。

### 2.2 Skill 当前资源链路

当前实现位于 `src/main/java/com/example/demo/agent/SkillRegistry.java`：

```text
classpath:skills/*/SKILL.md
  -> parseSkillDocument()
  -> validateSkillMeta()
  -> registerSkill()
  -> validateSkillLinks()
  -> indexSkillApis()
```

已确认的当前行为：

- `validateSkillMeta` 对 `links.name` 使用 `trim()` 做格式校验，但没有把 trim 后的名称
  写回 `SkillLink`；因此 frontmatter 中合法但带首尾空格的 link 可能在后续提示中带空格。
- `init()` 会从资源描述中提取 Skill 目录名，但当前只用于日志，没有检查它是否等于
  frontmatter 的 `name`。
- 分层 Skill 的 references 路径由 frontmatter name 拼接：
  `classpath:skills/<skillName>/references/...`。如果目录名和 frontmatter name 漂移，
  Skill 本体可能加载成功，而 references 或 operations 会找不到。
- 当前内置资源名称与目录名称一致；本批应把这一事实变成启动期契约，而不是依赖人工
  记忆。

影响：

- 模型得到的相关 Skill 名称可能不是可注册名称的规范形式；
- 分层 Skill 的“先加载目录、再读取 operation/reference”教学路径可能在资源重命名后
  静默失效或退回错误结果；
- 错误若延迟到模型调用阶段才出现，诊断成本高于启动期 fail-fast。

## 3. 实施范围与默认决策

### P0-A：知识库导入确定性

**修改文件**

- `src/main/java/com/example/demo/knowledge/KnowledgeBaseInitializer.java`
- `src/test/java/com/example/demo/knowledge/KnowledgeBaseInitializerTest.java`

**实施规则**

1. `readResource` 使用 `StandardCharsets.UTF_8`。
2. `loadKnowledgeBase` 收集每个资源后，以 metadata 中的 normalized `source` 去重；
   同一 source 只保留一个 Document。
3. 去重完成后按 source 字典序排序，再调用一次 `vectorStore.add`。
4. 当所有路径都没有文档时，保持现有“记录 warning、不调用 add”的行为。
5. 失败策略保持现状：
   - `fail-fast=true`：路径或资源读取异常继续抛出；
   - `fail-fast=false`：记录 warning/error 并继续处理其他路径。

**默认理由**

- UTF-8 是当前仓库 Markdown、中文 UI 和知识库文件的实际字符集；
- source 是代码已经选定的稳定身份，比 filename 更适合去重；
- 排序和去重只影响同一批导入的输入集合与顺序，不改变 Document ID、正文、metadata
  或 VectorStore 实现；
- 不在本批引入全局“已导入 source”缓存，避免改变应用重启后的 VectorStore upsert
  语义，也不把内存状态变成新的持久化事实。

**回滚边界**

如果未来 VectorStore 需要保留同名 source 的多个版本，应先扩展 metadata 中的版本或
生效时间，再放宽去重；本批不把文件名或内容 hash 作为新的业务 ID。

### P0-B：Skill 资源名称契约

**修改文件**

- `src/main/java/com/example/demo/agent/SkillRegistry.java`
- `src/test/java/com/example/demo/agent/SkillRegistryTest.java`

**实施规则**

1. `links.name` 先 trim，再执行既有格式校验，并把规范化值写回对象。
2. 增加一个仅负责来源路径契约的校验步骤：
   - 若 source 能解析出 `/skills/<directory>/SKILL.md` 或
     `skills/<directory>/SKILL.md`，则 `<directory>` 必须等于 frontmatter `name`；
   - 无法可靠解析目录的外部 Resource source 不强行拒绝，以保留未来 ResourceProvider
     扩展边界。
3. 目录漂移错误包含 Skill name 和 source，启动期抛出 `SkillDefinitionException`。
4. 不改变 `links` 的顺序，不新增环检测，不改变 API index 或 references 的路径协议。

**默认理由**

- trim 写回能保证 frontmatter 的宽容输入与运行时 canonical name 一致；
- 目录名是 classpath 分层资源定位的一部分，能解析时 fail-fast 比运行时返回“文件不存在”
  更适合 Demo 和生产诊断；
- 对不可解析的外部 source 保持宽松，避免为本批尚未实现的 SkillProvider 预设错误约束。

**回滚边界**

如果未来允许一个 Skill 映射多个资源目录，应引入显式 alias/provider metadata，而不是
直接取消目录契约。

### P1：教育文档与可发现性

**修改文件**

- `docs/knowledge-and-skills.md`
- `docs/learning-path.md`
- `docs/drafts/README.md`
- 本规划文档实施记录

**文档内容**

- 明确知识库按 UTF-8、normalized source 去重、source 排序后导入；
- 明确 `SKILL.md` 的 frontmatter name 是运行时 canonical name，并应与资源目录一致；
- 链接到对应生产源码和确定性测试；
- 不复制完整架构或配置，只补充读者理解“文档 -> VectorStore”和“目录 -> SkillRegistry”
  的关键契约。

## 4. 验收设计

必须先一次性编写以下测试，再修改生产实现：

| 场景 | 测试 | 通过标准 |
|---|---|---|
| 中文知识文档 | `KnowledgeBaseInitializerTest` | Document 正文保留中文字符，证明使用 UTF-8 |
| 重复 glob 去重 | `KnowledgeBaseInitializerTest` | 同一 source 配置两次只生成一个 Document |
| 稳定导入顺序 | `KnowledgeBaseInitializerTest` | source 逆序配置仍按 source 字典序传入 VectorStore |
| 既有 metadata/ID | `KnowledgeBaseInitializerTest` | stable ID、`kind`、`source`、filename 保持 |
| links 规范化 | `SkillRegistryTest` | 带首尾空格的合法 link 在 parse 后 canonical name 无空格 |
| 目录漂移拒绝 | `SkillRegistryTest` | 可解析 source 与 frontmatter name 不一致时 fail-fast，错误带 source |
| 当前资源回归 | `SkillRegistryTest` | `registry.init()` 仍成功发现现有 6 个 Skill 和 24 个 API index |

离线测试不得依赖真实 LLM、Embedding、PostgreSQL、浏览器或 AG-UI。

## 5. 实施顺序

1. 先完成本文和三轮连续无修改的规划审查；
2. 按验收设计扩展确定性测试，先让测试表达目标行为；
3. 实施知识库 UTF-8、去重和排序；
4. 实施 Skill link canonicalization 和来源目录契约；
5. 更新稳定文档导航和本规划实施记录；
6. 运行：

   ```bash
   git diff --check
   mvn clean compile test-compile
   mvn -Dtest='KnowledgeBaseInitializerTest,SkillRegistryTest,SkillReferenceReaderTest,SkillToolsTest,BackendApiIntegrationTest,ChatControllerTest' test
   mvn test
   ```

7. 对本批影响范围执行前端基本门槛和 Mock Playwright；如后端协议未改变，真实 LLM
   只复跑一次最小传统页面查询闭环，作为部署资源和模型配置没有回归的补充证据；
8. 基本门槛通过后执行三轮固定范围实现审查；发现影响正确性、安全、兼容性、成本或
   数据一致性的缺陷，修复后从硬门槛和第 1 轮重新开始；
9. 更新本文实施记录，提交并推送。

## 6. 不纳入本批

- ProductService 的库存扣减、订单持久化、购物车数量模型；
- REST DTO 全量重构或 API 路径变更；
- Spring AI 版本升级、Tool Search、社区 `SkillsTool` 替换；
- AG-UI/CopilotKit 状态、SSE 和浏览器工具；
- 知识库增量同步、删除失效文档、向量库跨重启迁移；
- 真实 RAG 质量评估和多轮模型 benchmark。

## 7. 中断恢复与回滚

- 若中断，先检查 `git status --short`，从本文第 5 节第一个未完成步骤继续；
- 知识库改动可以独立回滚，不改变 Skill 或普通 Agent 工具协议；
- Skill 目录契约和 link 规范化可以独立回滚，不改变当前 Skill 文件正文；
- 测试和文档回滚不会影响运行时，但应与对应实现一起回滚，避免文档描述超前。

## 8. 实施记录

### 8.1 实际修改

- `src/main/java/com/example/demo/knowledge/KnowledgeBaseInitializer.java`
  使用 UTF-8 读取 Markdown；以 normalized source 去重；按 source 排序后写入
  VectorStore；日志记录忽略的重复 source 数量。
- `src/test/java/com/example/demo/knowledge/KnowledgeBaseInitializerTest.java`
  覆盖中文正文、重复 source、稳定排序、stable ID 和 knowledge metadata。
- `src/main/java/com/example/demo/agent/SkillRegistry.java`
  将 `links.name` 规范化为 trim 后的 canonical name，并在可解析 Skill 来源中校验
  资源目录名与 frontmatter name 一致。
- `src/test/java/com/example/demo/agent/SkillRegistryTest.java`
  覆盖 link canonicalization 和目录/frontmatter 漂移拒绝；既有 6 Skill/24 API index
  回归仍保留。
- `docs/knowledge-and-skills.md`、`docs/learning-path.md`、`docs/drafts/README.md`
  增加知识库导入和 Skill 资源契约的可发现说明。

本批没有修改商品业务模型、Controller 路径、普通 Tool API、前端源码、
AG-UI/CopilotKit、Spring AI 版本或社区子模块。

### 8.2 验证结果

通过：

```text
mvn clean compile test-compile
mvn -Dtest='KnowledgeBaseInitializerTest,SkillRegistryTest,SkillReferenceReaderTest,SkillToolsTest,BackendApiIntegrationTest,ChatControllerTest' test
mvn test
cd frontend && npm run test:repository
cd frontend && npx tsc --noEmit
cd frontend && npm run build
cd frontend && npm run test:skills
cd frontend && npm run test:e2e:traditional:mock
```

结果：

- 本批相关测试：27 个全部通过；
- 默认 Maven 测试：36 个全部通过；
- 前端仓库复现性、TypeScript、生产构建和两个 Mock Playwright 套件全部通过；
- 使用用户提供的本地凭证，以 `postgresql` profile 和
  `RAG_ENABLED=true` 启动 `./dev.sh --backend-only`，日志确认 PostgreSQL、
  OpenAI-compatible `grok-4.5` 和 SiliconFlow Embedding 正常配置；
- 使用重复的 `KNOWLEDGE_BASE_PATHS` 真实启动，日志确认知识库最终导入 2 个唯一文档，
  忽略 1 个重复 source；
- 真实 `POST /api/chat/text` 知识库查询返回“自收到商品之日起 7 天内可申请退货”；
- 真实传统页面 Playwright E2E 完成登录、真实 `/api/chat/text` 请求和商品结果 DOM
  断言；未使用截图，期间未发现浏览器错误；
- 服务和测试端口已释放，密钥没有打印或写入仓库。

### 8.3 验证记录更正与审查计数重置

实施后第一次收敛审查的第 3 轮发现：仅用中文正文断言不能独立证明生产代码显式使用
UTF-8，因为当前开发环境默认字符集恰好也是 UTF-8；即使生产代码退回平台默认字符集，
该测试仍可能通过。这属于测试可证明性缺陷，按根目录 `AGENTS.md` 的规则立即修复并
将实现审查计数重置为 0。

处理措施：

- 在 `KnowledgeBaseInitializer` 增加 package-private 的 `readUtf8(InputStream)` 测试 seam，
  生产读取路径明确通过 `StandardCharsets.UTF_8`；
- 将 `KnowledgeBaseInitializerTest` 改为直接以 UTF-8 字节流验证解码契约；
- 重新执行 `mvn clean compile test-compile`、本批相关测试、完整 Maven 测试、前端基本
  门槛、Mock Playwright、真实 RAG 查询和真实传统页面 E2E；
- 重新从第 1 轮开始实施后三轮收敛审查。

截至 `2026-08-24 12:15:47 CST`，上述修复后的硬门槛和真实闭环均已通过；本节记录的
`27/36` 测试数字也已与 Surefire 报告一致。

随后进行提交前文档交叉审查时，又发现两处“最后核对”日期没有随本批修改同步：

1. `docs/drafts/README.md` 仍标记为 `2026-08-23`；修正为 `2026-08-24` 后将计数器重置
   为 0；
2. `docs/knowledge-and-skills.md` 仍标记为 `2026-08-23`；修正为 `2026-08-24` 后再次
   将计数器重置为 0。

这两次修正只涉及文档审计元数据，没有改变生产代码、测试断言或运行时行为。每次修正后
都重新从第 1 轮开始检查；最近一次修正后的三轮检查均无问题，结果记录在下表。

### 8.4 规划审查

规划文档在实施前连续完成三轮无修改审查：

| 轮次 | 检查时间 | 范围 | 结果 |
|---|---|---|---|
| 第 1 轮 | 2026-08-24 | 当前源码事实、实施边界、测试矩阵 | 一致，无修改 |
| 第 2 轮 | 2026-08-24 | 安全、兼容性、回滚和外部 ResourceProvider 边界 | 可行，无修改 |
| 第 3 轮 | 2026-08-24 | 测试可执行性、文档导航、中断恢复和排除范围 | 完整，无修改 |

### 8.5 实施后三轮收敛审查

最近一次文档元数据修正后的以下三轮均在基本集成验证通过后进行，范围固定为本批两个生产类、两组测试、
相关稳定文档和本规划。没有发现影响正确性、安全、兼容性、成本或数据一致性的缺陷，
期间没有修改代码或测试，连续计数达到 3：

| 轮次 | 检查时间 | 检查范围 | 发现与处理 | 结果 |
|---|---|---|---|---|
| 第 1 轮 | 2026-08-24 | UTF-8、source 去重/排序、stable ID/metadata、VectorStore 写入边界 | 未发现问题；无需处理 | 通过 |
| 第 2 轮 | 2026-08-24 | link canonicalization、目录契约、classpath/JAR 来源兼容、Skill 初始化回归 | 未发现问题；无需处理 | 通过 |
| 第 3 轮 | 2026-08-24 | 测试覆盖、文档入口、真实 RAG/传统页面证据、提交范围和产物 | 未发现问题；无需处理 | 通过 |

### 8.6 剩余边界

- 当前去重只作用于一次 `loadKnowledgeBase()` 调用，不引入跨重启导入缓存；
- 当前目录契约只校验能可靠解析出 `skills/<directory>/SKILL.md` 的 source，未来外部
  SkillProvider 仍需定义自己的身份协议；
- 真实 RAG 验证证明了当前配置和调用链可用，不等于对检索召回率、答案引用质量或所有
  Provider 做了 benchmark；
- AG-UI/CopilotKit 仍不在本批重点范围内。
