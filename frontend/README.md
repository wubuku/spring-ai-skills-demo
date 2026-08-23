# CopilotKit 前端

这是项目的 Next.js + CopilotKit v2 前端。它通过 Next.js BFF 将浏览器请求转发到 Spring Boot 的 AG-UI SSE 端点，并在浏览器侧执行需要用户身份和确认的 HTTP 工具。

## 技术基线

版本以 `package.json` 为准：

- Next.js `15.1.6`
- React `19`
- CopilotKit `1.60.x`
- `@ag-ui/client` `^0.0.47`
- TypeScript `5`
- Tailwind CSS `3`

## 运行

先启动 Java 后端：

```bash
mvn spring-boot:run -DskipTests
```

再在本目录安装和启动前端：

```bash
npm ci
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
- 请求前获取 `/api/agui/skills/api-index`，校验或纠正 API 路径。

不要恢复 v1 `useCopilotAction.renderAndWaitForResponse`，也不要在后端 AG-UI 配置中再次注册同名 `httpRequest` 或 `buildHttpRequest`。

## CSS 和构建支持文件

`next.config.js` 为 CopilotKit v2 CSS 和 Mermaid 入口配置了 webpack alias。`postinstall` 会调用：

- `scripts/transform-v2-css.mjs`
- `patches/copilotkit-v2-v3.css`
- `patches/stubs/mermaid-core-stub.mjs`

这些文件当前可能被 `.gitignore` 忽略。全新 clone 后运行 `npm ci` 或构建前，先确认它们存在；缺少时应先解决依赖来源和可复现性问题。

## 验证

```bash
npm run build
```

涉及 AG-UI、认证、浏览器工具或 SSE 时，还需要启动后端并参考：

- [项目架构](../docs/ARCHITECTURE.md)
- [REST 与 SSE API 参考](../docs/rest-api.md)
- [验证手册](../docs/HARNESS.md)
- [故障排查](../docs/troubleshooting.md)

根目录 `TEST_REPORT.md` 是带日期的历史测试记录，不代表当前每次构建或外部服务状态；不要从其中的 `3000`/`3001` 或“JWT”旧命名推断当前配置。
