# 知识库与运行时 Skills

> **目的**: 说明如何通过知识库文档回答业务规则类问题，以及如何通过运行时 Skills 让 Agent 按需使用业务 API。
> **文档状态**: 稳定指南；Spring AI 版本结论按下方“核查记录”维护。
> **最后核对**: 2026-08-24

## 先判断问题类型

本项目有两条不同的扩展路径：

| 需求 | 应放在哪里 | Agent 如何使用 |
|---|---|---|
| “公司的保修、退货、配送、服务条款是什么？” | `src/main/resources/knowledge-base/*.md` 或 `KNOWLEDGE_BASE_PATHS` 指定的路径 | RAG 检索相关文档片段，作为上下文回答 |
| “查询订单、创建售后单、申请退款、修改地址” | `src/main/resources/skills/<skill-name>/SKILL.md` | 先发现并加载 Skill，再调用其描述的工具/API |
| 既要查条款，又要执行操作 | 两者组合 | 先用知识库回答规则，再用 Skill/API 执行动作 |

相关技术实现的深入入口：

- [Spring AI 学习主线](learning-path.md)：按 REST、Tool Calling、运行时 Skills、记忆/RAG、SSE
  和 AG-UI 的顺序理解当前实现。
- [社区 `SkillsTool` 审计报告](spring-ai-agent-utils-audit.md)：固定对应仓库子模块
  `spring-ai-agent-utils/` 的 `v0.10.0` / `7f8bc47`。
- [当前项目 SKILL 支持改进规划](drafts/skill-support-improvement-plan.md)：从当前源码缺口
  出发，规划 frontmatter、reference 安全、API index、测试、状态隔离和未来 PoC。

**知识库是“事实内容”**；**Skill 是“如何完成服务动作的操作说明”**。不要把需要被检索的长篇政策文档全部写进 Skill，也不要把需要参数、认证和副作用说明的 API 操作只放进知识库。

## 路径一：通过知识库提供知识

### 当前实现

```text
Markdown 文档
  -> KnowledgeBaseInitializer
  -> Spring AI Document
  -> EmbeddingModel
  -> VectorStore
  -> QuestionAnswerAdvisor
  -> ChatClient / AgentService
  -> 回答中引用检索到的上下文
```

对应源码：

- 加载器：`src/main/java/com/example/demo/knowledge/KnowledgeBaseInitializer.java`
- 普通 Agent 编排：`src/main/java/com/example/demo/service/AgentService.java`
- RAG Advisor：`QuestionAnswerAdvisor`
- 向量记忆 Advisor：`VectorStoreChatMemoryAdvisor`
- 默认文档：`src/main/resources/knowledge-base/`
- 向量库配置：`src/main/java/com/example/demo/config/VectorStoreConfig.java`、`VectorStorePostgresqlConfig.java`

`QuestionAnswerAdvisor` 是 Spring AI 提供的 RAG 组件；本项目自定义的主要是“从哪些 Markdown 路径加载文档并写入 VectorStore”的启动胶水。Spring AI 核心文档中的 [Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)、[MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) 和 RAG/Vector Store 能力，是本项目可复用的基础设施。

### 当前入口边界

知识库 RAG 当前只接入普通 `AgentService`：

- `POST /api/chat`、`POST /api/chat/stream` 以及复用 `AgentService` 的多模态链路会经过 `QuestionAnswerAdvisor`。
- `AgUiConfig` 当前只为 `SpringAIAgent` 注册 `SkillsAdvisor` 和 `MessageChatMemoryAdvisor`，没有注册 `QuestionAnswerAdvisor` 或 `VectorStoreChatMemoryAdvisor`。
- 因此不能假设 Next.js/CopilotKit 的 `/api/agui` 链路已经具备同样的知识库问答能力。若要让 AG-UI 也回答公司条款，需要先把 RAG Advisor 接入该链路并补相应回归测试。

### 添加公司保修/服务条款

最简单的方式是在 `src/main/resources/knowledge-base/` 增加 Markdown 文件，例如：

```text
src/main/resources/knowledge-base/warranty-policy.md
```

文档应使用清晰的标题、条款编号、适用条件、例外情况和生效日期。例如：

```markdown
# 保修政策

## 适用范围
- 非人为损坏的主机产品，保修期为 12 个月。

## 不适用情形
- 进液、跌落、未授权拆机或超过保修期。

## 申请材料
- 订单号、购买凭证和故障描述。
```

默认配置会加载：

```text
classpath:knowledge-base/*.md
```

也可以通过环境变量指定多个 `classpath:` 或 `file:` glob：

```bash
SPRING_PROFILES_ACTIVE=local \
KNOWLEDGE_BASE_PATHS="classpath:knowledge-base/*.md,file:/opt/company-policies/*.md" \
mvn spring-boot:run -DskipTests
```

应用在 `ApplicationReadyEvent` 后加载匹配文件，并记录每个路径加载的文档数量。真实向量检索还需要可用的 Embedding 配置；非 PostgreSQL profile 使用 `SimpleVectorStore`，PostgreSQL profile 使用 `PgVectorStore`。详细变量和 profile 见 [配置参考](configuration.md)，可复制的验证步骤见 [操作示例](OPERATIONS.md)。

### 重要运行时边界

- 新增或修改文档通常需要重启应用，当前没有运行时热加载或增量同步接口。
- 当前加载器按 UTF-8 读取 Markdown，为规范化 source 生成稳定 UUID，并写入
  `metadata.kind=knowledge`、`source`、文件名等元数据。
- 同一轮加载中，如果多个 glob 重复命中同一 normalized source，只会向 VectorStore
  写入一次；写入前按 source 排序，使配置顺序和 Resource resolver 返回顺序不改变导入
  顺序。重复启动同一 source 会复用同一文档 ID，是否覆盖/更新由当前 VectorStore 实现
  按 ID 处理。
- 文档内容会进入向量检索和模型上下文，不要放入密钥、内部账号、未脱敏个人信息或不应发送给模型的数据。
- 知识库只负责提供上下文，不会自动执行退款、创建工单或修改订单。需要执行动作时必须另建 Skill/API 工具边界。

这些导入契约由
[`KnowledgeBaseInitializerTest`](../src/test/java/com/example/demo/knowledge/KnowledgeBaseInitializerTest.java)
确定性验证，不需要真实 Embedding 或 LLM。

## 路径二：通过运行时 Skills 提供服务

### 当前实现

```text
SKILL_LOCATIONS / app.skills.locations
  -> SkillResourceCatalog 发现 classpath、filesystem 或 JAR Skill
  -> SkillRegistry 启动扫描、解析 frontmatter、建立 API index
  -> SkillsAdvisor 注入 Level 1 技能目录
  -> loadSkill(name) 返回 Level 2 完整 SKILL.md
  -> readSkillReference(name, path) 返回 Level 3 分层参考
  -> httpRequest / buildHttpRequest
  -> 商品、PetStore 或其他业务 API
```

普通 Agent 的这条顺序由后端强制执行：`httpRequest`、`buildHttpRequest` 会根据
`SkillRegistry` API index 找到 API 所属 Skill，只有该 Skill 已在本轮
`SkillLoadSession` 中加载才会继续；普通 `readSkillReference` 也要求对应 Skill 已加载。
加载了 `get-product-detail` 不能访问 `search-products` 的 API，加载了任意其他 Skill
也不能读取 PetStore reference。未加载或缺少 `ToolContext` 时工具返回可读错误，且不会
发送下游 HTTP 请求或登记待确认写操作。

对应源码：

- Skill 资源发现和同源读取：`src/main/java/com/example/demo/agent/SkillResourceCatalog.java`
- Skill location 配置：`src/main/java/com/example/demo/config/SkillResourceProperties.java`
- Skill 注册和 API 索引：`src/main/java/com/example/demo/agent/SkillRegistry.java`
- Skill 只读观察目录：`src/main/java/com/example/demo/service/RuntimeSkillCatalogService.java`
- Skill 发现端点：`src/main/java/com/example/demo/controller/RuntimeSkillController.java`
- 动态目录和已加载内容：`src/main/java/com/example/demo/agent/SkillsAdvisor.java`
- Prompt 资源和降级：`src/main/java/com/example/demo/service/PromptLoader.java`
- 普通链路工具：`src/main/java/com/example/demo/agent/SkillTools.java`
- AG-UI 核心工具：`src/main/java/com/example/demo/agent/SkillCoreTools.java`
- AG-UI 工具注册：`src/main/java/com/example/demo/config/AgUiConfig.java`
- 当前 Skill 文件：`src/main/resources/skills/`
- 前端 HTTP 工具：`frontend/hooks/useHttpRequestTool.tsx`

### 新增一个售后服务 Skill

如果要让 Agent “查询售后单”或“创建保修申请”，典型步骤是：

1. 先实现并确认 Controller、DTO、认证和副作用边界。
2. 在 `src/main/resources/skills/after-sales/SKILL.md` 创建 Skill。
3. 添加 YAML frontmatter：

   ```yaml
   ---
   name: after-sales
   description: 查询售后政策、售后单和保修申请
   version: 1.0
   links:
     - name: search-products
       description: 查询商品和订单关联信息
   ---
   ```

4. 在正文中写清楚 API 路径、HTTP 方法、参数、认证要求、返回结构、错误边界和下一步 Skill。
5. 如果 API 较多，使用 `references/resources/`、`references/operations/`、`references/schemas/` 建立分层 Skill，让 `SKILL.md` 只保留导航和最小必要说明。
6. 修改后同步检查 Controller、运行时 Skill、`SkillRegistry` API index、提示词、前端 `httpRequest` URL 校验和测试脚本。
7. 通过普通 `/api/chat` 和 AG-UI `/api/agui` 分别验证。普通链路注册完整 `SkillTools`；AG-UI 后端只注册 `loadSkill`/`readSkillReference`，浏览器侧 `httpRequest` 负责带 Token 的请求和写操作确认。

平面 Skill 的核心是“文档描述 API”；分层 Skill 的核心是“先加载目录，再按需读取具体操作和 schema”。Skill 本身不是 Spring Bean，也不会被 Spring AI 自动扫描；它是本项目由 `SkillRegistry` 解释的资源格式。

`SKILL.md` frontmatter 中的 `name` 是运行时 canonical name，必须使用小写字母、数字和
连字符。对于 `src/main/resources/skills/<name>/SKILL.md` 这类可解析来源，目录名必须
与 frontmatter `name` 完全一致，否则应用在启动期拒绝该 Skill；`links.name` 会先
规范化首尾空格，再校验为已注册、非自身且不重复的 canonical name。这样分层 reference
始终从同一个 Skill 目录解析，而不是等模型调用时才暴露路径漂移。

目录、frontmatter、links 和当前资源图的契约由
[`SkillRegistryTest`](../src/test/java/com/example/demo/agent/SkillRegistryTest.java)
覆盖。

### 可复用 Skill 资源包

默认 `SKILL_LOCATIONS=classpath*:skills` 会加载当前应用资源，也会补扫 classloader
可枚举的普通依赖 JAR。一个最小 Skill JAR 可以使用以下布局：

```text
company-skills.jar
├── META-INF/MANIFEST.MF
└── skills/
    └── after-sales/
        ├── SKILL.md
        └── references/
            ├── operations/
            │   ├── get-case.md
            │   └── create-case.md
            └── schemas/
                └── after-sales-case.md
```

该 JAR 可以作为 Maven dependency 进入应用 classpath，或显式配置：

```bash
SKILL_LOCATIONS="classpath*:skills,jar:file:/opt/company-skills.jar!/skills"
```

外部目录使用：

```bash
SKILL_LOCATIONS="classpath*:skills,file:/opt/company-skills"
```

资源契约：

- 每个配置 root 必须至少发现一个 Skill，扫描失败不会静默回退到源码目录；
- JAR 即使没有 `skills/` 等目录 entry，也会按 entry 前缀扫描 `SKILL.md`；
- 当前应用打成 Spring Boot executable JAR 后，主应用 Skill 使用 `jar:nested:` URL；
  catalog 把正文、operation 和 reference 的相对解析继续固定在同一 nested 目录；
- Skill 正文、分层 operations、API index、结果解释和 `readSkillReference` 固定在同一个
  catalog source，不能从 JAR 加载正文后再回主应用 classpath 找 reference；
- 重复 canonical name、frontmatter/目录名漂移、悬空 links 和 API index 冲突仍
  fail-fast；
- filesystem source 拒绝配置 root 外的 Skill 和 Skill real root 外的 reference；
- source 只读，模型不能指定 `file:`/`jar:` URL，也不会因此获得 Shell、写文件或通用
  `FileSystemTools`。

修改外部目录或替换 JAR 后需要重启应用重建 registry/API index。实现 fixture 位于
[`SkillResourceCatalogTest`](../src/test/java/com/example/demo/agent/SkillResourceCatalogTest.java)，
覆盖 filesystem、classpath JAR、显式 JAR、无目录 entry、同源 operation/API 解释、
稳定排序、空 root 和符号链接越界。实际 executable JAR 的 nested URL 则由
[`test-executable-jar.sh`](../test-executable-jar.sh) 启动后断言 6 个 Skill 和 24 项
API index，并通过本地 Mock LLM 的 `/api/explain-result` 请求实际读取分层 operation
reference；两类验证都不需要真实 LLM、Embedding 或外部数据库。

### Prompt 资源与 fallback 契约

当前项目把 SkillsAdvisor 的提示词模板放在
`src/main/resources/prompts/skills-advisor/`，由
[`PromptLoader`](../src/main/java/com/example/demo/service/PromptLoader.java) 读取。
classpath 资源是正常运行时的事实来源；Java 中的 fallback 只用于资源缺失、测试 fixture
或受限打包环境，不能成为另一套工具协议。

因此修改以下任一内容时必须同步检查资源和 fallback：

- `loadSkill`、`readSkillReference`、`httpRequest`、`buildHttpRequest` 的名称或参数；
- frontend/AG-UI 与普通 backend 的 HTTP 执行边界；
- “必须先加载 Skill”、API index、写操作确认和停止条件。

[`PromptLoaderTest`](../src/test/java/com/example/demo/service/PromptLoaderTest.java) 使用正常
classpath 和不可用 ResourceLoader 对比三份 SkillsAdvisor 模板，并验证占位符替换与缓存
清理。它不调用 LLM、数据库或 Embedding，是检查 Prompt 与工具实现是否漂移的快速入口。
本批规划和实际修复记录见
[Prompt 资源与 fallback 契约加固规划](drafts/prompt-fallback-contract-hardening-plan.md)。

这项契约的教学意义是：Prompt 负责告诉模型如何发现和使用能力，Spring AI Tool schema
负责描述可调用形状，`SkillTools` 负责在后端强制执行 Skill/API 边界；三者不是互相替代
的实现。

### 无需 LLM 观察 Level 1/2/API index

运行后端后，开发者可以直接读取：

- `GET /api/skills`：Level 1 目录，只返回显式目录字段、links、分层标记和 API 数量；
- `GET /api/skills/{name}`：Level 2 Markdown body 和该 Skill 的 API index 条目；
- `GET /api/skills/api-index`：所有已登记 method/path 的稳定 allowlist；
- `GET /api/agui/skills/api-index`：旧兼容别名，与中性 index 返回相同 JSON。

详情中的 `referencePath` 只是 Level 3 导航指针。HTTP API 不返回 reference 正文，
模型仍必须调用受限 `readSkillReference`，因此观察能力不会扩大文件读取权限。

建议用确定性测试观察完整边界，而不是只看提示词：

```bash
mvn -Dtest='SkillToolsTest,SkillReferenceReaderTest,BackendApiIntegrationTest' test
```

其中正常回路证明 `loadSkill -> httpRequest/buildHttpRequest` 可以继续，负向回路证明
跳过 `loadSkill`、加载错误 Skill 或缺少请求上下文时不会执行 API。该门禁属于普通 Agent
工具边界，不是商品 Controller 的登录认证；它解决的是“是否先披露了操作指令”，而不是
“当前用户是否有权限访问业务资源”。

## 两条路径如何组合

例如用户问：

> “我的耳机还在保修期内吗？如果可以，请帮我申请维修。”

推荐的处理边界是：

1. 用 `after-sales` 或订单查询 Skill 获取订单/商品信息。
2. 用知识库检索保修政策。
3. 由 Agent 基于订单日期、商品类别和政策上下文判断是否满足条件。
4. 如果用户确认，再调用创建售后申请的 Skill/API。

政策条文不要硬编码进 Skill；API 路径和参数不要只写在知识库里。这样做可以分别更新政策内容和服务接口，降低文档与代码耦合。

## 与 Spring AI 最新能力的关系

### 核查记录

截至 **2026-08-23**：

- Spring AI 官方项目页和 GitHub Release 列表的最新稳定版本是 `2.0.1`。
- Spring AI `1.1.x` 的最新补丁版本是 `1.1.8`；本项目当前使用 Spring Boot `3.5.16`
  + Spring AI `1.1.8`。
- Spring AI `2.0.1` 源码基线使用 Spring Boot `4.1.1`，因此本项目不能把 BOM 从
  `1.1.8` 直接改成 `2.0.1` 后期待无改动通过。
- 本节的版本判断应在升级前重新核对官方 [项目页](https://spring.io/projects/spring-ai)、[Release 列表](https://github.com/spring-projects/spring-ai/releases) 和 [升级说明](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)。

### 三层能力边界

Spring AI 目前需要区分三层，而不是用“Spring AI 是否支持 SKILL”一句话概括：

| 层次 | 能力 | 与本项目的关系 |
|---|---|---|
| Spring AI 核心 `2.0.1` | RAG/Vector Store、`@Tool`/`ToolCallback`、MCP、`ToolSearchToolCallingAdvisor` | 提供底层检索、工具和动态工具发现；不定义本项目的 `SKILL.md` 业务协议 |
| Spring AI Community `spring-ai-agent-utils` | `SkillsTool`：Markdown + YAML frontmatter、语义匹配、reference/helper files、渐进式披露 | 已有一套与本项目相近的 Skills 实现，但它是社区库，不是 `org.springframework.ai` 核心模块；当前社区仓库源码基线使用 Spring AI `2.0.0` |
| 当前项目 | `SkillRegistry`、`SkillsAdvisor`、`loadSkill`/`readSkillReference`、Skill `links`、API index、AG-UI/前端确认 | 面向本项目业务 API 和双 Agent 链路的定制实现 |

核心能力的具体含义：

| Spring AI 能力 | 能替代什么 | 不能直接替代什么 |
|---|---|---|
| `@Tool`、`ToolCallback`、`ToolCallingAdvisor` | 工具定义、工具执行循环 | 本项目的 `SKILL.md` 文件格式和业务文档图 |
| `ToolSearchToolCallingAdvisor` | 对大量工具做渐进式工具发现，只把发现的工具加入后续请求 | 不会自动把 Markdown API 指令、`links`、`references/` 和本项目 API index 变成同一套业务 Skill |
| MCP Client/Server | 跨进程发现和消费工具、resources、prompts | 不等于本地 `src/main/resources/skills/` 文件加载器 |
| RAG、Vector Store、`QuestionAnswerAdvisor` | 文档检索和知识问答 | 不负责定义可执行 API 操作和用户确认流程 |

Spring AI 核心的 [Tool Search Tool](https://docs.spring.io/spring-ai/reference/api/tools/tool-search-tool.html) 会按会话索引工具，初始只向模型提供搜索工具，再把发现到的工具加入后续请求；[MCP 支持](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)则标准化暴露 tools、resources 和 prompts。这些能力可以替代“工具发现”或“跨进程工具暴露”的部分，但不等于本地 Skill 文件系统。

另一方面，[Spring AI Community 的 `spring-ai-agent-utils`](https://github.com/spring-ai-community/spring-ai-agent-utils) 已经提供 [SkillsTool](https://github.com/spring-ai-community/spring-ai-agent-utils/blob/main/spring-ai-agent-utils/docs/SkillsTool.md)：支持 Markdown Skill、frontmatter、目录/JAR 资源加载和按需返回 Skill 正文。它与本项目的文件格式和理念有明显重叠，但必须作为独立社区依赖评估，不应写成 Spring AI 核心已经内置了本项目的 `SkillRegistry`。

### 结论：是不是重新发明轮子？

**不是完全重新发明，也不是完全没有重复。**

- 知识库路径基本复用了 Spring AI 的 RAG 基础设施；`KnowledgeBaseInitializer` 是项目级 Markdown 导入适配。
- Skills 路径复用了 Spring AI 的 `@Tool`、`ChatClient` 和 Advisor，但 `SkillRegistry`、Level 1/2/3、Skill `links`、分层 references、API index 和 AG-UI/前端确认边界是本项目自己的领域层。
- Spring AI 2.0.1 的 Tool Search 可以部分替换“工具发现”部分；`spring-ai-agent-utils` 可以作为现成 Skills 实现进行 PoC 对比，但都不是当前实现的无缝替换。
- 当前项目确实重复实现了部分“Skill 文件发现、frontmatter 解析、按需加载”基础机制。它的额外价值在于把这些机制与 API index、业务端点校验、普通 Agent/AG-UI 双链路和浏览器确认流程绑定在一起。
- 如果目标只是构建通用 Markdown Skills Agent，应优先评估 `spring-ai-agent-utils`；如果目标是大量 Java 工具的 Token 优化，应优先评估 Spring AI 核心 Tool Search；如果目标是跨进程/跨语言服务暴露，应优先评估 MCP。

因此当前项目更准确的定位是：**在 Spring AI 基础能力之上，实现了一层面向业务 API 的文档化服务技能协议**，而不是重新实现 Spring AI 的 RAG 或 Tool Calling。

在决定迁移前，建议先做一个小型 PoC：

1. 用 `spring-ai-agent-utils` 加载现有一个平面 Skill，验证 frontmatter、正文和 references 的兼容程度。
2. 对比现有 `links`、API index、URL 校验和 AG-UI 前端确认是否需要保留自定义扩展。
3. 单独用 `ToolSearchToolCallingAdvisor` 测试大量 `ToolCallback` 时的 Token 与工具命中率，不要把它和 Markdown Skills 迁移绑成一次改造。
4. 只有在 Spring Boot/Spring AI 主版本升级和 AG-UI 兼容验证通过后，再决定替换 `SkillRegistry`/`SkillsAdvisor`。

### `spring-ai-agent-utils` 质量与迁移评估

本节保留决策摘要，完整的版本化源码审计、测试覆盖、frontmatter 兼容性和风险证据见
[《Spring AI Community `SkillsTool` 审计报告》](spring-ai-agent-utils-audit.md)。报告对应
当前仓库的 `spring-ai-agent-utils/` 子模块 `v0.10.0`
（`7f8bc47de1bc5a306b6cb078fa6b191ff7845572`）；更新子模块后应以审计报告为准重新核对，
不要只沿用本节的旧结论。

**结论先行：社区库本身值得关注，但当前项目不应直接切换。**

#### 质量判断

截至 **2026-08-23**，固定的 `v0.10.0` 上游仓库具备以下积极信号：

- Apache-2.0 许可证，已发布到 Maven Central；当前最新正式版为 `0.10.0`，仍属于 `0.x` API 阶段。
- 有独立的 `SkillsToolTest`、`MarkdownParserTest`，覆盖文件系统目录、Spring `Resource`、JAR/Classpath JAR 和基础调用结果。
- 有 GitHub Actions CI、Maven Central 发布流程和 GraalVM Runtime Hints；JAR 资源扫描比当前项目的启动扫描实现更系统。
- 代码量、示例和文档都比较完整，适合作为 Spring AI 2.0 时代的通用 Agent 工具库参考。

但这更接近“**可用于实验、示例和内部工具的中上质量社区库**”，还不能等同于针对企业业务 API 的成熟 Skill 平台。当前公开的主要风险包括：

- 正式版本仍是 `0.10.0`，主分支已经进入 `0.11.0-SNAPSHOT`，API 仍可能变化。
- `SkillsTool` 本身只是注册一个名为 `Skill` 的 `FunctionToolCallback`，把全部 Skill 元数据放进工具描述，由模型选择 `command`。这里的“语义匹配”本质上是模型根据描述做选择，不是独立的向量/检索匹配器。
- `SkillsTool` 的实现只读取 `frontMatter["name"]`，其余字段主要原样渲染到工具描述。文档中的 `allowed-tools`、`model` 在 `SkillsTool` 中没有对应的权限执行或模型路由逻辑；这些字段不能被当作安全策略。
- `reference files` 和 `helper scripts` 不是 `SkillsTool` 自己的受限读取/执行 API。Skill 返回基础目录，模型还需要另外获得 `FileSystemTools`、`ShellTools` 等工具；这会扩大本地文件和命令执行边界。
- `MarkdownParser` 是逐行的轻量解析器，不是完整 YAML 解析器。它不支持本项目 `links` 的嵌套列表，也不能保留 OpenAPI Skill 的嵌套 `metadata` 结构。
- 上游仍有与生产部署相关的公开问题：多 SkillsJar 依赖只发现一个资源、Spring Boot fat JAR 的 `jar:nested:` 扫描、GraalVM native image、非 OpenAI 模型工具调用、可插拔 SkillProvider 和权限声明等。对应问题见 [#28](https://github.com/spring-ai-community/spring-ai-agent-utils/issues/28)、[#32](https://github.com/spring-ai-community/spring-ai-agent-utils/issues/32)、[#37](https://github.com/spring-ai-community/spring-ai-agent-utils/issues/37)、[#50](https://github.com/spring-ai-community/spring-ai-agent-utils/issues/50)、[#56](https://github.com/spring-ai-community/spring-ai-agent-utils/issues/56)、[#59](https://github.com/spring-ai-community/spring-ai-agent-utils/issues/59)。

审计还用当前项目的 Skill 文件做了兼容性试读：`add-to-cart` 的原始描述“将指定商品加入用户购物车”会被轻量解析器后面的关联 Skill 描述覆盖，`links` 列表也不会得到结构化结果。因此当前 `src/main/resources/skills/` 不能原样交给社区库。

#### 与当前项目的适配矩阵

| 能力 | `spring-ai-agent-utils` | 当前项目 | 判断 |
|---|---|---|---|
| 文件系统和 Classpath/JAR Skill 发现 | 较完整，有专门扫描和测试 | `SkillResourceCatalog` 支持 filesystem、classpath/显式 JAR、无目录 entry，并通过实际 Boot JAR smoke 覆盖 `jar:nested:` | 当前项目已吸收并验证主要 JVM 部署形态；native image 仍未覆盖 |
| 结构化 frontmatter | 当前实现使用 Jackson YAML，能处理当前 `links` 文件格式；OpenAPI `metadata` 仍未映射到模型 | 上游轻量解析器不兼容当前 `links` 文件 | 当前项目更贴合 |
| Level 1/2/3 渐进披露 | Level 1 目录 + 一个 Skill 工具返回完整正文；references 依赖额外文件工具 | `SkillsAdvisor`、`loadSkill`、`readSkillReference` 明确分层 | 当前项目更贴合 |
| Skill `links` 图 | 有文档正文可自行描述，但没有对应结构化 API | `SkillMeta.links` + 提示词链路 | 当前项目更强 |
| API index 和 URL 校验 | 没有业务 API 索引或 URL 白名单 | `SkillRegistry` 建索引，Java/浏览器侧校验 | 当前项目不可替代 |
| 写操作确认和认证边界 | 不提供业务确认流程；脚本/文件工具边界需自行治理 | AG-UI 浏览器 `httpRequest`、Token 和确认流程已绑定 | 当前项目不可替代 |
| Spring Boot / Spring AI 版本 | 发布版基于 Spring AI `2.0.0`、Spring Framework `7.0.8` | Spring Boot `3.5.16`、Spring AI `1.1.8` | 不能直接混用 |
| 可复用包和上游测试 | Maven Central、独立测试和发布流程 | 已有统一 catalog、file/JAR fixture、Boot nested JAR smoke、Registry/reference/工具与后端回合测试；尚未把 catalog 发布为独立库 | 社区库在独立发布和 native image 覆盖上仍更成熟 |

#### 是否值得切换

对当前项目，答案是：**现在不值得直接切换**。

原因不是社区库没有价值，而是替换后仍必须保留本项目的大部分领域层：

1. 必须保留或重写真实 YAML 解析，以支持 `links`、版本；如果要保留 OpenAPI `metadata`，还要先扩展当前 `SkillMeta` 模型。
2. 必须保留 `SkillRegistry` 的 API index、路径参数匹配、`/api/agui` 暴露和 URL 校验。
3. 必须保留 `readSkillReference` 的受限资源读取，不能把业务 API Skill 的 references 自动降级为任意文件读取或 Shell 执行。
4. 必须继续维护普通 `AgentService` 与 AG-UI/CopilotKit 的双工具边界、前端 Token 和写操作确认。
5. 还需要先把项目从 Spring Boot `3.5.16` / Spring AI `1.1.8` 升级到社区库的 Spring AI 2.0 基线，并重新验证 AG-UI、工具参数解析和多模型 Provider。

因此直接引入后，短期得到的主要是更好的资源扫描和上游测试，短期付出的是主版本升级、Skill 格式迁移和业务安全边界重写；收益不足以覆盖风险。分阶段改进路线见
[当前项目 SKILL 支持改进规划](drafts/skill-support-improvement-plan.md)。

#### 推荐策略

- **当前阶段**：继续使用项目自有 `SkillRegistry`/`SkillsAdvisor`；已增加统一
  `SkillResourceCatalog`、frontmatter/API index/path/reference、file/JAR fixture 和
  Boot nested JAR smoke；后续重点是独立包发布与 native image，而不是替换生产工具协议。
- **若目标是通用 Markdown Skills**：在独立分支做小型 PoC，使用一个没有 `links` 的平面 Skill，验证 `SkillsTool` 与 Spring AI 2.0、实际模型和可执行 JAR 的组合。
- **若目标是大量 Java 工具的 Token 优化**：优先评估 Spring AI 核心 `ToolSearchToolCallingAdvisor`，不要把它和 Markdown Skill 迁移绑定。
- **若目标是跨进程/跨语言能力**：优先评估 MCP，而不是把本地 Markdown 文件加载器当成服务协议。
- **若目标是贡献上游**：最有价值的改进方向是完整 YAML 解析、`SkillProvider` SPI、受限 reference API、权限声明/验证和 Spring Boot fat JAR/native image 回归测试；当前项目的 nested JAR scope 与 smoke 脚本可作为独立参考，但不代表上游问题已经修复。

## 发现路径

- 想配置公司政策、保修、配送、支付等知识：先读 [配置参考](configuration.md) 的“知识库”，再读 [操作示例](OPERATIONS.md) 的“记忆和 RAG”。
- 想新增查询、创建、审批、售后等服务动作：先读本页“运行时 Skills”，再读 [系统架构](ARCHITECTURE.md) 和 `src/main/resources/skills/` 中的真实样例。
- 想把一组 Skills 作为外部目录或 Maven/JAR 依赖复用：读本页
  [可复用 Skill 资源包](#可复用-skill-资源包)，再查 [配置参考](configuration.md#运行时-skill-资源)。
- 想了解 Spring AI 原生能力与本项目自定义层的边界：读本页“与 Spring AI 最新能力的关系”，再回到 `pom.xml` 和官方 Tool Calling/MCP/RAG 文档。
- 想判断社区 `SkillsTool` 是否值得替换当前实现：直接阅读本页的 [`spring-ai-agent-utils` 质量与迁移评估](#spring-ai-agent-utils-质量与迁移评估)。
