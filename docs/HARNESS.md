# 验证手册

> **目的**: 根据改动范围选择最小的验证命令，并正确解释外部服务失败。
> **最后核对**: 2026-08-23

## 验证分层

| 层级 | 命令/入口 | 适用范围 | 外部依赖 |
|---|---|---|---|
| 文档格式 | `git diff --check` | 所有文档改动 | 无 |
| Git 范围 | `git status --short` | 提交前 | 无 |
| 后端编译 | `mvn -DskipTests clean package` | Java、配置、资源和 Skill | Maven 仓库 |
| Maven 测试 | `mvn test` | Java 测试和上下文 | 可能访问外部 LLM |
| 前端构建 | `cd frontend && npm run build` | Next.js、CopilotKit、CSS、TypeScript | Node 依赖和本地 patch |
| 基础回归 | `./test.sh` | 商品、聊天、认证 | 后端、`.env`、LLM |
| PetStore | `./test-petstore.sh` | 分层 OpenAPI Skill | 后端、LLM |
| 记忆/RAG | `./test-vector-store-memory.sh`, `./test-rag-knowledge-base.sh` | 向量记忆和知识库 | Embedding、数据库/profile |
| 多模态 | `./test-multimodal.sh`, `./test-streaming-transcribe.sh` | 图片、音频、转写 | 视觉/转写服务和测试文件 |
| 流式 | `./test-streaming.sh` | 普通和多模态 SSE | 后端、LLM、测试文件 |
| AG-UI | `./test-agui-jwt-full.sh`, `./test-sse-jwt.sh` | AG-UI、认证和 SSE | 后端、LLM、认证 |
| 浏览器 E2E | `python test-e2e-frontend.py` | Next.js + CopilotKit | 后端 8080、前端 4000、Playwright |

## 标准序列

### 文档或 Java 改动

```bash
git diff --check
mvn -DskipTests clean package
git status --short
```

### 前端改动

```bash
cd frontend
npm ci
npm run build
```

如果 `postinstall` 引用的 `scripts/transform-v2-css.mjs`、`patches/copilotkit-v2-v3.css` 或 Mermaid stub 不存在，先检查工作区来源和 `.gitignore`，不要把构建失败直接归因于业务代码。

### 端点、Skill 或 Agent 工具改动

1. 启动后端并确认 `/api/agui/health`、`/api/agui/info`。
2. 访问 `/v3/api-docs` 或 Swagger 检查 Controller 映射。
3. 访问 `/api/agui/skills/api-index` 检查 Skill API index。
4. 运行与端点对应的专项脚本。
5. 如涉及浏览器 `httpRequest`，再运行前端 E2E 或手工确认流程。

## Maven 测试的边界

`mvn test` 包含 `@SpringBootTest` 和 DeepSeek/LLM 相关测试，不能在没有凭证、网络或匹配 profile 的环境下视为离线测试。报告结果时区分：

- 编译或 Spring ApplicationContext 失败。
- 外部模型、Embedding、视觉、转写服务失败。
- PostgreSQL/pgvector 连接或 schema 失败。
- Agent 工具选择、SSE 或前端行为失败。

## 测试记录

根目录 `TEST_REPORT.md` 是带日期的历史测试报告。它可以提供场景和输出样例，但不能替代当前命令；报告中的端口、认证命名和工具架构必须回到源码核对。
