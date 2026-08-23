# 知识库与运行时 Skills

> **目的**: 说明如何通过知识库文档回答业务规则类问题，以及如何通过运行时 Skills 让 Agent 按需使用业务 API。
> **文档状态**: 稳定指南；Spring AI 版本结论按下方“核查记录”维护。
> **最后核对**: 2026-08-23

## 先判断问题类型

本项目有两条不同的扩展路径：

| 需求 | 应放在哪里 | Agent 如何使用 |
|---|---|---|
| “公司的保修、退货、配送、服务条款是什么？” | `src/main/resources/knowledge-base/*.md` 或 `KNOWLEDGE_BASE_PATHS` 指定的路径 | RAG 检索相关文档片段，作为上下文回答 |
| “查询订单、创建售后单、申请退款、修改地址” | `src/main/resources/skills/<skill-name>/SKILL.md` | 先发现并加载 Skill，再调用其描述的工具/API |
| 既要查条款，又要执行操作 | 两者组合 | 先用知识库回答规则，再用 Skill/API 执行动作 |

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
- 当前加载器把匹配到的文档直接 `vectorStore.add(...)`；持久化向量库在重复启动时可能再次写入相同文档，当前实现没有按 `source` 做去重。
- 文档内容会进入向量检索和模型上下文，不要放入密钥、内部账号、未脱敏个人信息或不应发送给模型的数据。
- 知识库只负责提供上下文，不会自动执行退款、创建工单或修改订单。需要执行动作时必须另建 Skill/API 工具边界。

## 路径二：通过运行时 Skills 提供服务

### 当前实现

```text
src/main/resources/skills/*/SKILL.md
  -> SkillRegistry 启动扫描、解析 frontmatter、建立 API index
  -> SkillsAdvisor 注入 Level 1 技能目录
  -> loadSkill(name) 返回 Level 2 完整 SKILL.md
  -> readSkillReference(name, path) 返回 Level 3 分层参考
  -> httpRequest / buildHttpRequest
  -> 商品、PetStore 或其他业务 API
```

对应源码：

- Skill 注册和 API 索引：`src/main/java/com/example/demo/agent/SkillRegistry.java`
- 动态目录和已加载内容：`src/main/java/com/example/demo/agent/SkillsAdvisor.java`
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
- Spring AI `1.1.x` 的最新补丁版本是 `1.1.8`；本项目当前使用 Spring Boot `3.4.2` + Spring AI `1.1.2`。
- Spring AI `2.0.1` 源码基线使用 Spring Boot `4.1.1`，因此本项目不能把 BOM 从 `1.1.2` 直接改成 `2.0.1` 后期待无改动通过。
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

另一方面，[Spring AI Community 的 `spring-ai-agent-utils`](https://github.com/spring-ai-community/spring-ai-agent-utils) 已经提供 [SkillsTool](https://github.com/spring-ai-community/spring-ai-agent-utils/blob/main/spring-ai-agent-utils/docs/SkillsTool.md)：支持带 YAML frontmatter 的 Markdown Skill、语义匹配、reference/helper files 和 progressive disclosure。它与本项目的文件格式和理念有明显重叠，但必须作为独立社区依赖评估，不应写成 Spring AI 核心已经内置了本项目的 `SkillRegistry`。

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

## 发现路径

- 想配置公司政策、保修、配送、支付等知识：先读 [配置参考](configuration.md) 的“知识库”，再读 [操作示例](OPERATIONS.md) 的“记忆和 RAG”。
- 想新增查询、创建、审批、售后等服务动作：先读本页“运行时 Skills”，再读 [系统架构](ARCHITECTURE.md) 和 `src/main/resources/skills/` 中的真实样例。
- 想了解 Spring AI 原生能力与本项目自定义层的边界：读本页“与 Spring AI 最新能力的关系”，再回到 `pom.xml` 和官方 Tool Calling/MCP/RAG 文档。
