# 项目文档

> 中文为主。本文是仓库文档导航，不替代源码、配置文件、Swagger/OpenAPI 或运行时 Skill。

## 阅读路径

| 读者 | 起点 | 下一步 |
|---|---|---|
| 第一次运行项目 | [根 README](../README.md) | [开发指南](DEVELOPMENT.md)、[配置参考](configuration.md) |
| 修改 Agent 或工具 | [架构说明](ARCHITECTURE.md) | [运行时 Skills](../src/main/resources/skills/)、[API 参考](rest-api.md) |
| 修改前端或 AG-UI | [架构说明](ARCHITECTURE.md) | [前端指南](../frontend/README.md)、[CopilotKit 集成说明](../COPILOTKIT_INTEGRATION.md) |
| 运行测试或回归 | [验证手册](HARNESS.md) | [测试报告](../TEST_REPORT.md) |
| 排查启动、模型、SSE 或工具问题 | [故障排查](troubleshooting.md) | [历史诊断材料](#草稿与历史材料) |
| 配置公司知识或新增服务能力 | [知识库与运行时 Skills](knowledge-and-skills.md) | [配置参考](configuration.md)、[系统架构](ARCHITECTURE.md) |
| 评估社区 `SkillsTool` 或规划当前 Skills 改进 | [社区库审计报告](spring-ai-agent-utils-audit.md) | [SKILL 支持改进规划](drafts/skill-support-improvement-plan.md) |
| 给 Agent 提供仓库上下文 | [AGENTS.md](../AGENTS.md) | [project-docs Skill](../.agents/skills/project-docs/SKILL.md) |

## 稳定指南

| 文档 | 内容 | 当前事实来源 |
|---|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 后端、前端、AG-UI、Skills、记忆和数据流 | Java/TypeScript 源码 |
| [DEVELOPMENT.md](DEVELOPMENT.md) | 安装、启动、profile、前端和日常开发 | `pom.xml`、`package.json`、配置文件 |
| [configuration.md](configuration.md) | LLM、Embedding、数据库、视觉、转写和知识库配置 | `.env.example`、`application*.yml`、配置类 |
| [rest-api.md](rest-api.md) | 当前 Controller 端点、认证边界和 SSE 形状 | Controllers、运行时 Skills、API index |
| [OPERATIONS.md](OPERATIONS.md) | REST、聊天、记忆/RAG、多模态和 Docker 操作示例 | Controllers、测试脚本、Dockerfile/Compose |
| [HARNESS.md](HARNESS.md) | 编译、Maven、Shell、前端和 E2E 验证矩阵 | 测试源码和脚本 |
| [troubleshooting.md](troubleshooting.md) | 按症状排查常见问题 | 当前实现和已验证记录 |
| [knowledge-and-skills.md](knowledge-and-skills.md) | 知识库问答、运行时 Skills、扩展步骤和 Spring AI 能力边界 | `KnowledgeBaseInitializer`、`AgentService`、`SkillRegistry`、`SkillsAdvisor`、官方 Spring AI 文档 |
| [spring-ai-agent-utils-audit.md](spring-ai-agent-utils-audit.md) | 固定社区子模块 `v0.10.0` 的 `SkillsTool` 源码、测试、兼容性和迁移评估 | `spring-ai-agent-utils/` 子模块 |

## 组件和报告入口

| 文档 | 说明 |
|---|---|
| [根 AGENTS.md](../AGENTS.md) | Agent 使用的唯一当前状态导航 |
| [根 CLAUDE.md](../CLAUDE.md) | 兼容入口，只跳转到 `AGENTS.md` |
| [COPILOTKIT_INTEGRATION.md](../COPILOTKIT_INTEGRATION.md) | CopilotKit 集成记录，部分内容属于历史背景 |
| [COPILOTKIT_INTEGRATION_GUIDE.md](COPILOTKIT_INTEGRATION_GUIDE.md) | 通用 CopilotKit/AG-UI 集成参考；当前项目事实以稳定指南为准 |
| [frontend/README.md](../frontend/README.md) | 前端目录的开发入口 |
| [TEST_REPORT.md](../TEST_REPORT.md) | 带日期的测试记录，不替代当前验证命令 |
| [spring-ai-model-abstraction.md](spring-ai-model-abstraction.md) | Spring AI Provider 和模型抽象补充说明；参考文档，修改前需回到配置类核对 |
| [project-docs Skill](../.agents/skills/project-docs/SKILL.md) | 本仓库文档整理工作流 |

## 草稿与历史材料

`docs/drafts/` 保存正在调查的方案、诊断过程和历史经验。它们可以解释“为什么做出某个决定”，但不自动代表当前架构。使用前必须回到源码和稳定指南核对。详细分类见 [草稿索引](drafts/README.md)。

重点材料：

- [DOCUMENTATION_PLAN.md](drafts/DOCUMENTATION_PLAN.md)：本轮文档体系建设计划。
- [skill-support-improvement-plan.md](drafts/skill-support-improvement-plan.md)：当前项目 SKILL
  支持改进规划、P0 实施记录和后续 P1/P2/P3 路线；继续实施前须重新完成规划文档的三轮
  连续无修改检查。
- [copilotkit-native-tool-call-lessons-learned.md](copilotkit-native-tool-call-lessons-learned.md)：前后端工具协作的历史经验，适合理解 AG-UI 工具边界。
- [PROBLEM_INVESTIGATION.md](PROBLEM_INVESTIGATION.md)：确认模式问题的历史排查记录，不能单独作为当前实现依据。
- [spring-ai-agui-guide.md](drafts/spring-ai-agui-guide.md)：AG-UI 调研草稿，内容可能包含已替代方案。
- 其余草稿按文件名区分为方案、诊断、比较和进度记录；不要把 `*.bak`、截图或日志产物当作稳定文档。

## 文档生命周期

| 类型 | 位置 | 规则 |
|---|---|---|
| 当前导航 | 根目录、`docs/`、组件 README | 必须与源码和配置保持一致 |
| 稳定指南 | `docs/*.md` | 面向重复使用的开发、架构和运维信息 |
| 草稿 | `docs/drafts/` | 计划、诊断、研究和未决方案，需标注状态 |
| 测试记录 | 根 `TEST_REPORT.md` 或专项报告 | 带日期和前置条件，不替代自动化验证 |
| 运行时 Skill | `src/main/resources/skills/` | LLM 调用业务 API 的指令源 |
| Agent Skill | `.agents/skills/` | Agent 执行仓库任务的可移植工作流 |

## 更新规则

1. 先修改事实来源，再更新稳定指南。
2. 端点变更同时检查 Controller、运行时 Skill、API index、前端工具 schema 和测试脚本。
3. 配置文档只写变量名、默认值和前置条件，不写密钥。
4. 发现旧文档漂移时，优先在本索引中标注边界，再做针对性纠偏。
5. 文档改动至少运行 `git diff --check`；涉及 Java 或前端命令时运行对应构建验证。
