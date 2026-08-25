# 文档体系建设计划

> **状态**: 2026-08-25 可发现性迭代已实施、验证并完成收敛审查
> **目的**: 为 `spring-ai-skills-demo` 建立以当前源码为事实来源、面向人类和 Agent 的分层文档入口。
> **最后核对**: 2026-08-25

## 文档分层

```text
README.md
  -> docs/README.md
      -> 稳定指南：架构、开发、配置、API、验证、故障排查
      -> 组件入口：frontend/README.md、COPILOTKIT_INTEGRATION.md
      -> 事实来源：Controllers、配置文件、运行时 Skills、测试脚本
      -> docs/drafts/：进行中的计划、诊断和历史经验
```

## 任务清单

| 优先级 | 文档 | 事实来源 | 状态 | 验证 |
|---|---|---|---|---|
| P0 | `README.md` | 当前功能、`pom.xml`、`frontend/package.json` | 已完成 | 快速开始、端口、导航 |
| P0 | `docs/README.md` | 仓库文档目录与文档生命周期 | 已完成 | 链接和状态索引 |
| P0 | `AGENTS.md` | 当前源码、配置和协作边界 | 已完成 | 源码事实审阅 |
| P1 | `docs/ARCHITECTURE.md` | Java/TypeScript/AG-UI 请求流 | 已完成 | 源码路径核对 |
| P1 | `docs/DEVELOPMENT.md` | `pom.xml`、`package.json`、profile、脚本 | 已完成 | build、frontend build |
| P1 | `docs/HARNESS.md` | Maven 测试、`test-*.sh`、Playwright | 已完成 | 命令和外部依赖审阅 |
| P2 | `docs/configuration.md` | `.env.example`、`application*.yml`、配置类 | 已完成 | 配置键和默认值核对 |
| P2 | `docs/rest-api.md` | Controllers、运行时 Skills、API index | 已完成 | 端点路径核对 |
| P2 | `docs/troubleshooting.md` | 当前实现、已验证报告、历史诊断 | 已完成 | 症状与当前方案分离 |
| P2 | `docs/OPERATIONS.md` | Controllers、测试脚本、Dockerfile/Compose | 已完成 | 操作示例和前置条件审阅 |
| P2 | `frontend/README.md` | `frontend/package.json`、App Router、hooks | 已完成 | 4000 端口和 v2 工具边界 |

## 二次审计任务

本轮不是重建目录，而是针对上一轮稳定文档做源码对照和可执行性修正：

| 主题 | 事实来源 | 状态 | 处理 |
|---|---|---|---|
| Demo Token 两种生成格式及校验边界 | `AuthService`、`AuthProvider.tsx`、测试脚本 | 已完成 | 在 `AGENTS.md`、架构、REST、前端和故障排查文档中统一说明 |
| 普通 Agent 与 AG-UI 工具清单 | `SkillTools`、`SkillCoreTools`、`AgUiConfig` | 已完成 | 补全普通链路的 `buildHttpRequest`、`readSkillReference` 边界 |
| 测试脚本是否自动启动后端 | `test-*.sh` | 已完成 | 在验证手册中拆分自启动/已有服务两类 |
| Profile 与 RAG 启动示例 | `application.yml`、`application-*.yml`、操作文档 | 已完成 | 明确 `postgresql` 根配置和 `local` 覆盖方式 |
| 前端同 tab 登录后的 BFF Token 同步 | `AuthProvider.tsx`、`CopilotProvider.tsx` | 已完成 | 增加当前限制和刷新排查路径 |
| 参考文档生命周期 | `docs/spring-ai-model-abstraction.md` | 已完成 | 增加状态和最后核对日期 |
| 链接、格式、构建和工作区复核 | project-docs checklist | 已完成 | `git diff --check`、214 个 Markdown 相对链接、Maven 打包和前端构建均通过 |

## 明确不做的事

- 不把所有 `docs/drafts/` 重写成稳定文档。
- 不把草稿中的旧端口、旧 JWT 表述、旧确认模式或旧工具架构作为当前事实。
- 不新增 `MEMORY.md`、`.cursor/rules/` 或第二份根 Agent 规则。
- 不修改 Java/TypeScript 业务代码；本轮只整理文档和导航。

## 验证命令

```bash
git diff --check
git status --short
mvn -DskipTests clean package
cd frontend && npm run build
```

前端构建和行为测试依赖本地 `frontend/scripts/`、`frontend/patches/`、Node 依赖及必要的外部服务；缺少这些前置条件时，记录为环境限制，不把失败误判为文档问题。

## 上一轮结果

- 已建立根 README -> `docs/README.md` -> 稳定指南/组件入口/草稿索引的导航链。
- 已将根 README 和 `frontend/README.md` 收敛为当前事实入口。
- 已补充操作示例指南，承接根 README 收敛前的 REST、聊天、记忆/RAG、多模态和 Docker 操作材料。
- 已为 `COPILOTKIT_INTEGRATION.md` 和 `TEST_REPORT.md` 增加历史状态说明。
- 已验证 `git diff --check`、相对链接、后端打包和前端生产构建。

## 剩余风险

- `docs/drafts/` 中的历史方案仍可能包含不平衡代码围栏、旧端口、绝对路径或旧认证命名；它们已被索引明确为非当前事实，本轮不进行大规模重写。
- `docs/COPILOTKIT_INTEGRATION_GUIDE.md` 是通用 CopilotKit 集成参考，代码示例仍可能与项目专用配置不同；当前端口和工具边界以稳定指南为准。
- Docker Compose 与当前 `application.yml` 仍存在历史默认值差异，详见 [配置参考](../configuration.md)。

## 二次审计结果

- 已修正登录 API、前端和测试脚本之间的 Token 载荷描述差异，并明确当前校验逻辑的局限。
- 已补全普通 `SkillTools` 与 AG-UI `SkillCoreTools` 的工具边界。
- 已将测试脚本按后端启动责任拆分，避免直接运行 AG-UI/多模态专项脚本时误判为代码故障。
- 已修正无 PostgreSQL 时的 RAG 启动示例，补充 8080 端口冲突提醒。
- 已补充前端同 tab 登录后的 BFF headers 同步限制。
- 已通过 project-docs Skill 的绝对路径/密钥扫描、Markdown 相对链接检查、`mvn -DskipTests clean package` 和 `cd frontend && npm run build`。

## 后续增量：可发现性与 Spring AI SKILL 核查

> **状态**: 已完成
> **最后核对**: 2026-08-23

- 新增稳定指南 [knowledge-and-skills.md](../knowledge-and-skills.md)，把“知识库文档”和“运行时 Skills”拆成两条读者路径。
- 在根 `README.md`、`AGENTS.md`、`docs/README.md`、`docs/configuration.md` 和 `docs/ARCHITECTURE.md` 增加发现入口。
- 记录 Spring AI `2.0.1` 核心的 Tool Search、MCP、RAG 能力，以及 Spring AI Community `spring-ai-agent-utils` 的 `SkillsTool`。
- 明确当前项目不是重复实现 Spring AI 的 RAG/Tool Calling，但确实自定义实现了部分与社区 SkillsTool 重叠的文件发现和渐进式加载机制；是否替换应按“通用 Skills、工具 Token 优化、跨进程服务”三个目标分别评估。

## 后续增量：社区 SkillsTool 质量与迁移评估

> **状态**: 已完成
> **最后核对**: 2026-08-23

- 审阅 `spring-ai-agent-utils` 的 `SkillsTool`、`Skills`、`MarkdownParser`、专项测试、Maven Central POM、CI 和公开 issue。
- 结论：社区库适合 Spring AI 2.0 时代的通用 Skills 实验和内部工具，但当前项目不应直接切换。
- 关键阻塞项：发布基线是 Spring AI `2.0.0`/Spring Framework `7.0.8`；轻量 frontmatter 解析器不兼容当前 `links`/嵌套 metadata；没有本项目的 API index、URL 校验、受限 references 和 AG-UI 前端确认边界。
- 评估矩阵、风险、兼容性试读结果和 PoC 条件已落在 [knowledge-and-skills.md](../knowledge-and-skills.md)。

## 后续增量：固定源码基线与 SKILL 支持改进规划

> **状态**: 文档已落档；改进规划尚未实施
> **最后核对**: 2026-08-23

- 将 `spring-ai-community/spring-ai-agent-utils` 引入为 `spring-ai-agent-utils/` Git 子模块。
- 子模块固定到正式 release `v0.10.0`，提交为 `7f8bc47de1bc5a306b6cb078fa6b191ff7845572`。
- 新增 [社区库审计报告](../spring-ai-agent-utils-audit.md)，报告中的源码路径、版本和提交与子模块对应。
- 新增 [SKILL 支持改进规划](skill-support-improvement-plan.md)，覆盖当前 frontmatter、reference
  安全、API index、状态隔离、资源扫描、渐进式披露、观测、测试和 Spring AI 2.x PoC。
- `AGENTS.md` 已记录规划先行、自包含、主动推进和“三轮连续无修改”审查规则；`CLAUDE.md`
  仍保持纯重定向。

## 2026-08-25 增量：project-docs 移植复核与功能可发现性

> **状态**: 已实施、验证并完成收敛审查
> **目标**: 复核从
> `/Users/yangjiefeng/.hermes/workspace/seedance-research/.agents/skills/project-docs`
> 移植而来的本地 Skill 包确实自包含，并让开发者/Agent 可以从用户问题或项目功能直接
> 定位稳定说明、生产代码、配置/资源和自动化验证。

### 当前事实

- 本地包已经位于 `.agents/skills/project-docs/`，包含 `SKILL.md`、
  `references/checklist.md` 和 `references/templates.md`，三者均已被 Git 跟踪。
- 本地 `SKILL.md` 已按 Spring Boot/Spring AI、中文主文档、运行时 Skills 与 Agent
  Skills 边界、规划/验收规则做过项目适配；运行时不依赖原始 Hermes 工作区。
- 根 `README.md` 已提供常见扩展入口，`docs/README.md` 已按读者场景导航，
  `docs/knowledge-and-skills.md` 与 `docs/learning-path.md` 已深入解释知识库和运行时
  Skills。
- 当前缺少一张稳定的横向索引，把“功能或问题”同时连接到入口端点、生产代码、
  配置/资源、确定性测试和深入文档。相关信息虽然存在，但读者需要在架构、API、
  学习路径和 `AGENTS.md` 之间自行拼接。
- 本地 Skill 的仓库地图和检查清单尚未把功能到 owner 代码/验证证据的横向索引列为
  推荐的可发现性层。

### 设计决策

1. 新增 `docs/feature-map.md` 作为稳定横向索引，不重复解释实现细节。每一行只回答：
   “功能入口在哪里、由什么代码负责、受什么配置/资源控制、用什么测试证明、去哪里
   深入阅读”。
2. 功能地图按读者任务组织，至少覆盖：商品 REST、普通 Agent、运行时 Skills、知识库
   RAG、PetStore Mock 与分层 OpenAPI Skill、记忆、写操作确认、结果解释、多模态/SSE、
   Demo 认证、传统页面、AG-UI/CopilotKit、开发与验证，以及 `.agents/skills/` Agent
   工作流。
3. 在根 `README.md`、`docs/README.md` 和 `AGENTS.md` 增加近距离入口；不把功能地图内容
   复制到三个导航文档。
4. 更新 `.agents/skills/project-docs/`，让后续执行该 Skill 时把“功能 -> 代码 -> 验证”
   横向索引视为可选但优先的可发现性工具，并把它纳入审计清单。包内只保留相对
   reference 链接，不引用原始 Hermes 路径。
5. `CLAUDE.md` 不增加任何规则，继续只跳转到 `AGENTS.md`。

### 实施文件

| 优先级 | 文件 | 修改 |
|---|---|---|
| P0 | `.agents/skills/project-docs/SKILL.md` | 补充功能地图策略、自包含检查和导航维护规则 |
| P0 | `.agents/skills/project-docs/references/checklist.md` | 增加功能到代码/测试可发现性检查 |
| P0 | `docs/feature-map.md` | 新建稳定的功能、代码、配置、测试和文档横向索引 |
| P0 | `README.md` | 给人类读者增加功能地图入口 |
| P0 | `docs/README.md` | 把功能地图加入阅读路径和稳定指南 |
| P0 | `AGENTS.md` | 给 Agent 增加功能地图入口，并记录其维护触发条件 |
| P1 | `docs/drafts/README.md` | 保持本计划在草稿入口中的状态准确 |
| P1 | `docs/drafts/DOCUMENTATION_PLAN.md` | 实施后记录结果和验证证据 |

### 事实来源与交叉验证

- 端点和请求边界：`src/main/java/com/example/demo/controller/`。
- PetStore Mock 领域：`src/main/java/com/example/demo/petstore/`、
  `src/main/resources/petstore.yaml` 和分层运行时 Skill。
- Agent、Skills 和工具：`src/main/java/com/example/demo/agent/`、
  `src/main/java/com/example/demo/service/AgentService.java`。
- 知识库、记忆和外部配置：`src/main/java/com/example/demo/knowledge/`、
  `src/main/java/com/example/demo/config/`、`src/main/resources/application*.yml`。
- 运行时资源：`src/main/resources/skills/`、`src/main/resources/prompts/`、
  `src/main/resources/knowledge-base/`、`src/main/resources/static/index.html`。
- 前端：`frontend/app/`、`frontend/components/`、`frontend/hooks/`、
  `frontend/package.json`。
- 验证证据：`src/test/`、`frontend/tests/`、`test-*.sh`、`dev.sh`。
- 现行说明：`docs/ARCHITECTURE.md`、`docs/rest-api.md`、`docs/HARNESS.md`、
  `docs/knowledge-and-skills.md` 和 `docs/learning-path.md`。

### 验收标准

1. 从根 `README.md`、`docs/README.md` 或 `AGENTS.md` 最多一次跳转即可进入功能地图。
2. 从功能地图最多一次跳转即可到达该功能的主要生产代码、确定性测试或深入文档。
3. “通过指定知识库文档提供知识”和“通过指定运行时 Skills 提供服务”必须各有独立行，
   且清楚区分 `.agents/skills/` 的 Agent 工作流。
4. 功能地图中的本地文件链接、目录和测试类全部存在；端点、端口、配置名与当前源码一致。
5. Skill 包内没有原始工作区依赖、用户主目录绝对路径、密钥或包外必需文件；所有
   `SKILL.md` reference 链接均相对解析。
6. `CLAUDE.md` 内容不扩张；稳定文档不把 `docs/drafts/` 当作当前事实来源。
7. `git diff --check`、Markdown 本地链接/锚点检查和敏感信息扫描通过。
8. 本轮不修改运行时代码，因此不以真实 LLM、数据库或浏览器 E2E 作为文档正确性的
   必要证据；若文档引入新的构建/测试命令，则额外执行对应命令。

### 风险与可逆边界

- 风险：横向索引可能复制过多实现细节并快速过期。控制方式是只列 owner 路径、验证入口
  和深入文档，不复制参数表、配置默认值或协议正文。
- 风险：历史草稿可能含旧结论。功能地图只链接稳定文档和当前源码/测试；草稿只作为
  决策历史单独标注。
- 风险：目录链接可发现但不够精确。优先链接 owner 类或测试类；只有一组资源本身就是
  事实来源时才链接目录。
- 回滚边界：可单独移除 `docs/feature-map.md` 及四处导航增量，不影响代码、配置、
  运行时 Skills 或已有文档内容。

### 规划审查与继续实施

规划完成后按根 `AGENTS.md` 的重置计数规则执行三轮：

1. 当前事实和功能覆盖：源码、配置、资源、测试与功能地图计划是否对应。
2. 信息架构和自包含：入口跳转、文档生命周期、Skill 包依赖和术语边界是否清楚。
3. 验收和维护成本：链接检查是否可执行、是否避免重复、后续变更触发条件是否明确。

只有连续三轮未修改本计划，才开始修改稳定文档和 Skill 包。

### 实施结果

- 保留源 Skill 包的三个文件结构，并在本地包中继续使用相对 `references/`；没有引入
  源工作区、用户主目录或机器本地依赖。
- 新增稳定索引 `docs/feature-map.md`，覆盖业务 API、普通 Agent、运行时 Skills、
  可复用 Skill 包、PetStore/OpenAPI、知识库、记忆、模型、Prompt、多模态、认证、
  传统页面、AG-UI、开发验证和仓库内 Agent Skill。
- 根 `README.md`、`docs/README.md` 和 `AGENTS.md` 均可一步进入功能地图；
  `docs/README.md` 与 `AGENTS.md` 已记录功能地图的维护触发条件。
- `project-docs/SKILL.md` 已把横向功能地图纳入推荐文档层、事实来源矩阵、写作流程和
  最终审查；`references/checklist.md` 已增加对应检查项。
- `CLAUDE.md` 未修改，继续只作为 `AGENTS.md` 的兼容入口。

### 基础验证结果

| 验证 | 结果 |
|---|---|
| `git diff --check` | 通过 |
| Markdown 本地链接与锚点 | 22 个当前入口文档、355 个本地链接通过 |
| 功能地图 owner/资源/测试目标 | 82 个链接目标全部存在 |
| Skill 包绝对路径/源工作区/密钥扫描 | 通过 |
| `mvn clean compile test-compile` | 通过，133 个主源码和 18 个测试源码可编译 |
| `mvn -Dtest='*Skill*Test,*Api*Test' test` | 54/54 通过 |
| `CLAUDE.md` diff | 无变化 |

本轮没有修改运行时代码、前端实现或外部服务配置，因此不运行真实 LLM、数据库容器或
浏览器 E2E；这些测试不会为纯文档索引改动提供额外正确性证据。
