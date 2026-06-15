"use client";

import * as React from "react";
import { CopilotKitProvider } from "@copilotkit/react-core/v2";

/**
 * v2 CopilotKit Provider (B1 重构 — 2026-06-13)
 *
 * 从 v1 `CopilotKit` 切换到 v2 `CopilotKitProvider`：
 * - v1 `CopilotKit` 不识别 v2 context，导致 `useHumanInTheLoop` / `useFrontendTool`
 *   注册的工具对后端不可见，整个聊天被阻塞（0 agent/run 请求，回归）
 * - v2 `CopilotKitProvider` 创建 CopilotKitCoreReact 实例，挂载 v2 context，
 *   让 v2 hooks 注册的工具能被 runtime 正确转发
 *
 * 关键 props:
 * - runtimeUrl: BFF 路径（v2 与 v1 共享同一 BFF，使用 v1 @copilotkit/runtime）
 * - headers: 注入 JWT 认证头（同 v1 逻辑）
 * - useSingleEndpoint={true}: 必填！
 *
 *   不设这个会导致 v2 客户端默认走 "rest" transport，把请求发到：
 *   - GET  ${runtimeUrl}/info
 *   - POST ${runtimeUrl}/agent/${id}/run
 *   - POST ${runtimeUrl}/agent/${id}/connect
 *
 *   但我们的 BFF（`app/api/copilotkit/route.ts` 用 `copilotRuntimeNextJSAppRouterEndpoint`）
 *   内部是 `createCopilotEndpointSingleRoute`（@copilotkitnext/runtime hono-single.mjs），
 *   只挂了一个 `POST /` 路径，body 是 JSON 信封 `{ method, params, body }`。
 *
 *   开启 useSingleEndpoint 后，v2 客户端 transport 切到 "single"：
 *   - POST ${runtimeUrl}  body={ method: "info" }
 *   - POST ${runtimeUrl}  body={ method: "agent/run", params: { agentId }, body: <input> }
 *   - POST ${runtimeUrl}  body={ method: "agent/connect", params: { agentId }, body: <input> }
 *
 *   这与 BFF 的 single-route 信封完全一致，无需改 BFF。
 */
/**
 * 读取当前 auth token（同步、纯客户端）。
 * 用作 CopilotKitProvider 的 headers prop —— 1.54 v2 的 headers 类型是
 * `Record<string, string>`（不是函数），所以必须是同步的。
 *
 * token 存放在 localStorage（key=auth_token），由 AuthProvider 写入。
 */
function readAuthHeaders(): Record<string, string> {
  if (typeof window === "undefined") return {};
  const token = localStorage.getItem("auth_token");
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export function CopilotProvider({ children }: { children: React.ReactNode }) {
  // 初始渲染时读一次 token（用户在登录后 setItem → 刷新页面就会读到）
  // 如果 token 在运行中变化（登出/切换账号），触发一次 re-mount 即可同步
  const [headers, setHeaders] = React.useState<Record<string, string>>(() =>
    readAuthHeaders()
  );

  React.useEffect(() => {
    // 监听 storage 事件（跨 tab 登录登出）以及自定义 auth-changed 事件（同 tab 登出/登录）
    const sync = () => setHeaders(readAuthHeaders());
    window.addEventListener("storage", sync);
    window.addEventListener("auth-changed", sync);
    return () => {
      window.removeEventListener("storage", sync);
      window.removeEventListener("auth-changed", sync);
    };
  }, []);

  return (
    <CopilotKitProvider
      runtimeUrl="/api/copilotkit"
      headers={headers}
      useSingleEndpoint
    >
      {children}
    </CopilotKitProvider>
  );
}