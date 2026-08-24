# 开发指南

> **目的**: 说明本地构建、启动、profile 选择、前端开发和常用工作流。
> **最后核对**: 2026-08-24

## 前置条件

- JDK 17+。
- Maven 3.8+。
- Node.js 22.19+ 和 npm，用于 `frontend/`。
- 一个可用的 OpenAI-compatible、Anthropic 或 MiniMax 模型配置。
- 使用 `postgresql` profile 时，需要 PostgreSQL、`vector` 扩展和对应凭证。

Dockerfile 构建和运行均使用 Java 17；修改 Java 或 Docker 配置时同时检查 `pom.xml`
和 `Dockerfile`。

## 后端

### 配置环境变量

```bash
cp .env.example .env
# 编辑 .env，填入密钥；不要提交 .env
set -a && source .env && set +a
```

### 一键启动开发环境

推荐使用根目录脚本统一启动后端和前端：

```bash
./dev.sh
```

脚本读取根目录 `.env`，默认启动：

- Spring Boot：`http://localhost:8080`
- Next.js：`http://localhost:4000`

默认 profile 为 `postgresql`；如果 `.env` 提供 `SPRING_DATASOURCE_URL`、
`SPRING_DATASOURCE_DRIVER_CLASS_NAME` 以及 `POSTGRES_USER`/`POSTGRES_PASSWORD`，
脚本会补齐 Spring datasource 用户名和密码。可使用
`./dev.sh --backend-only`、`./dev.sh --frontend-only` 或
`./dev.sh --stop` 控制组件。端口可通过 `BACKEND_PORT` 和 `FRONTEND_PORT` 覆盖。

### 编译和运行

```bash
# 只验证编译和打包，不调用外部 LLM
mvn -DskipTests clean package

# 启动 Spring Boot，默认端口 8080
mvn spring-boot:run -DskipTests
```

默认访问：

- 静态页面：`http://localhost:8080/`
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`
- H2 控制台：`http://localhost:8080/h2-console`，仅非 `postgresql` profile 有意义

### Profile

根 `src/main/resources/application.yml` 不自动激活 profile。直接运行 Maven 时默认使用
H2；`dev.sh` 在未覆盖时默认设置 `postgresql`，以匹配本地 PostgreSQL 开发环境。
本地不使用 PostgreSQL 时可显式选择非 PostgreSQL profile：

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -DskipTests
```

此时使用 H2 文件数据库和非 PostgreSQL 的 `SimpleVectorStore`。`application-dev.yml`
只有在显式使用 `dev` profile 时才生效。RAG 和语义向量记忆默认关闭，只有打开对应
开关时才要求 Embedding 配置。

### 切换模型 Provider

```bash
# OpenAI-compatible，默认
LLM_PROVIDER=openai

# Anthropic-compatible
LLM_PROVIDER=anthropic

# Spring AI 原生 MiniMax
LLM_PROVIDER=minimax
```

对应完整变量见 [配置参考](configuration.md)。

## 前端

```bash
cd frontend
npm ci --registry=https://registry.npmjs.org
npm run test:repository
npm run dev
```

前端开发服务器固定使用 `4000` 端口：

- UI：`http://localhost:4000`
- BFF：`http://localhost:4000/api/copilotkit`
- Java 后端地址：由 `JAVA_BACKEND_URL` 和 `NEXT_PUBLIC_JAVA_BACKEND_URL` 分别控制服务端与浏览器端访问。

`frontend/scripts/transform-v2-css.mjs` 和
`frontend/patches/copilotkit-v2-v3.css` 已纳入 Git。`npm ci` 会按锁定的
CopilotKit 版本重新生成 CSS；`npm run test:repository` 会在不修改工作区的情况下检查
脚本、入口引用、依赖安全固定值、官方 registry lockfile 和生成物一致性。全新 clone
不需要从其他工作区恢复构建支持文件。
`next.config.js` 将 `outputFileTracingRoot` 固定为当前 `frontend/` 目录，避免父目录
其他 lockfile 影响 Next.js 的构建追踪范围。

当前固定 Next.js 15.5.23、CopilotKit 1.60.2 和 Undici 8.10.0。Undici 的引擎约束
要求 Node.js 22.19+；`package.json` 还对 Next 的 PostCSS/Sharp 和旧 AI SDK 的
Undici 传递依赖做了精确安全覆盖。生产依赖审计使用：

```bash
npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org
```

## 传统页面 Playwright E2E

内嵌在 Spring Boot 的传统页面使用普通 Agent 的同步接口
`POST /api/chat/text`。完成 Mock/构建门槛并确认 `.env` 中的真实模型可用后：

```bash
./dev.sh --backend-only
cd frontend
npm run test:e2e:traditional
```

测试覆盖页面 DOM、登录、认证网络请求、真实聊天响应状态和商品结果 DOM；不使用截图。
首次运行通常需要 `npx playwright install chromium`。如果浏览器下载源不可用且机器
已有 Chrome，可使用 `PLAYWRIGHT_BROWSER_CHANNEL=chrome npm run test:e2e:traditional`；
测试断言和覆盖范围不变。

## 常用开发顺序

1. 先定位事实来源：Controller、Service、配置类、运行时 Skill、前端 hook 和测试脚本。
2. 修改端点时同步更新 `src/main/resources/skills/`、API index、提示词、前端工具 schema 和回归脚本。
3. 后端改动运行 `mvn -DskipTests clean package`。
4. 前端改动运行 `cd frontend && npm run build`。
5. 涉及真实 Agent、认证、流式或外部服务时，按 [验证手册](HARNESS.md) 选择专项脚本。
6. 提交前运行 `git diff --check`、`git status --short` 和子模块状态检查。

## 不应默认执行的命令

- 默认 `mvn test` 排除 `live-llm` 和 `container` 标签，是确定性/Mock 测试；
  真实 provider 和 PostgreSQL 测试必须显式启用。
- `test-*.sh` 通常要求后端已启动、`.env`、外部 API 或 PostgreSQL。
- 多模态测试需要 `TEST_IMAGE_PATH`、`TEST_AUDIO_PATH`。
- AG-UI 浏览器测试需要后端 8080、前端 4000 和 Playwright。
