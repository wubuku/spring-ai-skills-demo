# CopilotKit 前端

这是项目的 Next.js + CopilotKit v2 前端。它通过 Next.js BFF 将浏览器请求转发到 Spring Boot 的 AG-UI SSE 端点，并在浏览器侧执行需要用户身份和确认的 HTTP 工具。

## 技术基线

版本以 `package.json` 为准：

- Node.js `22.19+`
- Next.js `15.5.23`
- React `19`
- CopilotKit `1.60.2`
- `@ag-ui/client` `^0.0.47`
- TypeScript `5`
- Tailwind CSS `3`

## 运行

从仓库根目录使用 `.env` 一键启动后端和前端：

```bash
../dev.sh
```

也可以分别启动：

先启动 Java 后端：

```bash
mvn spring-boot:run -DskipTests
```

再在本目录安装和启动前端：

```bash
npm ci --registry=https://registry.npmjs.org
npm run dev
```

开发服务器使用 `http://localhost:4000`，生产预览使用同一端口：

```bash
npm run build
npm start
```

## 环境变量

`.env.local` 至少可以配置：

```dotenv
JAVA_BACKEND_URL=http://localhost:8080
NEXT_PUBLIC_JAVA_BACKEND_URL=http://localhost:8080
```

- `JAVA_BACKEND_URL`：Next.js 服务端 BFF 访问 Java 后端。
- `NEXT_PUBLIC_JAVA_BACKEND_URL`：浏览器侧登录和 `httpRequest` 访问 Java 后端。

## 当前请求链路

```text
CopilotKit v2 UI
  -> CopilotKitProvider(runtimeUrl="/api/copilotkit", useSingleEndpoint)
  -> app/api/copilotkit/route.ts
  -> CopilotRuntime + HttpAgent
  -> POST http://localhost:8080/api/agui
  -> SpringAIAgent
```

关键文件：

| 文件 | 职责 |
|---|---|
| `app/layout.tsx` | 挂载 `CopilotProvider` |
| `components/CopilotProvider.tsx` | v2 Provider、single endpoint 和认证 headers |
| `app/api/copilotkit/route.ts` | BFF、转发 Authorization 到 Java |
| `app/page.tsx` | 页面、登录入口、CopilotPopup 和消息渲染 |
| `components/AuthProvider.tsx` | 登录、登出和 `localStorage.auth_token` |
| `hooks/useHttpRequestTool.tsx` | 浏览器 HTTP 工具、URL 校验和人在回路 |

## 浏览器 `httpRequest` 工具

AG-UI 模式下后端只注册 `loadSkill` 和 `readSkillReference`。浏览器侧 `useHttpRequestTool` 注册唯一的 `httpRequest`：

- `GET` 自动执行。
- `POST`、`PUT`、`PATCH`、`DELETE` 在 `executing` 状态显示确认 UI。
- 确认后通过 `respond()` 将结果返回给 CopilotKit，触发后端下一轮 Agent run。
- 从 `localStorage.auth_token` 读取 Demo token，发送 `Authorization: Bearer ...`。
- 请求前获取旧兼容路径 `/api/agui/skills/api-index`，只允许索引中的相对路径和路径参数
  匹配。嵌入式传统页面和新客户端使用中性 `/api/skills/api-index`；两个端点由后端同一
  catalog service 生成相同 JSON。

不要恢复 v1 `useCopilotAction.renderAndWaitForResponse`，也不要在后端 AG-UI 配置中再次注册同名 `httpRequest` 或 `buildHttpRequest`。

## 认证状态同步限制

`AuthProvider` 登录或登出时会更新 React state 和 localStorage，但当前没有派发 `auth-changed` 自定义事件；`CopilotProvider` 的 BFF headers 可能因此继续使用页面首次加载时的 Token。浏览器侧 `httpRequest` 每次直接读取 localStorage，表现可能与 BFF 不一致。

如果同一个页面内刚登录就出现 AG-UI 401/403，先刷新页面再重试。这个说明描述的是当前实现限制，不代表认证 Token 已具备 JWT 的签名、过期或完整性保护。

## CSS 和构建支持文件

`next.config.js` 为 CopilotKit v2 CSS 和 Mermaid 入口配置了 webpack alias。以下构建支持
文件已由 Git 跟踪：

- `scripts/transform-v2-css.mjs`
- `patches/copilotkit-v2-v3.css`

`postinstall` 会按 `package-lock.json` 中的 CopilotKit 版本生成 CSS 补丁。
`npm run test:repository` 会在不修改工作区的情况下检查安装入口、固定依赖、
官方 registry lockfile 和生成物一致性；如果生成物过期，使用 npm 官方 registry
重新执行 `npm ci`。
`next.config.js` 同时把 Next.js 的 `outputFileTracingRoot` 固定在当前目录，避免父目录
其他 lockfile 改变构建追踪范围。

## 验证

```bash
npm run test:repository
npm run build
npm run test:skills
npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org
```

内嵌在 Spring Boot 的传统页面真实 E2E（先启动根目录后端并准备 `.env` 中的真实
LLM/Embedding 配置）：

```bash
npm run test:e2e:traditional
```

该测试使用 Playwright headless Chromium，覆盖页面 DOM、登录、`/api/auth/verify`、
`/api/chat/text` 和最终商品结果；不使用截图。它验证普通 Agent，不是
Next.js/CopilotKit AG-UI 流程。
首次运行可执行 `npx playwright install chromium`；若下载源不可用且机器已有 Chrome，
使用 `PLAYWRIGHT_BROWSER_CHANNEL=chrome npm run test:e2e:traditional`。

涉及 AG-UI、认证、浏览器工具或 SSE 时，还需要启动后端并参考：

- [项目架构](../docs/ARCHITECTURE.md)
- [REST 与 SSE API 参考](../docs/rest-api.md)
- [验证手册](../docs/HARNESS.md)
- [故障排查](../docs/troubleshooting.md)

根目录 `TEST_REPORT.md` 是带日期的历史测试记录，不代表当前每次构建或外部服务状态；不要从其中的 `3000`/`3001` 或“JWT”旧命名推断当前配置。
