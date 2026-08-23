# 文档体系建设计划

> **状态**: 已完成
> **目的**: 为 `spring-ai-skills-demo` 建立以当前源码为事实来源、面向人类和 Agent 的分层文档入口。
> **最后核对**: 2026-08-23

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

## 本轮结果

- 已建立根 README -> `docs/README.md` -> 稳定指南/组件入口/草稿索引的导航链。
- 已将根 README 和 `frontend/README.md` 收敛为当前事实入口。
- 已补充操作示例指南，承接根 README 收敛前的 REST、聊天、记忆/RAG、多模态和 Docker 操作材料。
- 已为 `COPILOTKIT_INTEGRATION.md` 和 `TEST_REPORT.md` 增加历史状态说明。
- 已验证 `git diff --check`、相对链接、后端打包和前端生产构建。

## 剩余风险

- `docs/drafts/` 中的历史方案仍可能包含不平衡代码围栏、旧端口、绝对路径或旧认证命名；它们已被索引明确为非当前事实，本轮不进行大规模重写。
- `docs/COPILOTKIT_INTEGRATION_GUIDE.md` 是通用 CopilotKit 集成参考，代码示例仍可能与项目专用配置不同；当前端口和工具边界以稳定指南为准。
- Docker Compose 与当前 `application.yml` 仍存在历史默认值差异，详见 [配置参考](../configuration.md)。
