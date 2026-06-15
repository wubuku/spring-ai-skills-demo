"use client";

import React from "react";
import { useHumanInTheLoop } from "@copilotkit/react-core/v2";
import { z } from "zod";

/**
 * HTTP 请求参数 Schema
 * 注意：v2 (CopilotKit 1.60.1) 的 useHumanInTheLoop 期望 Zod 3.24+ Standard Schema
 * （不再支持 v1 的 Parameter[] 数组格式）
 *
 * 重构说明（2026-06-13 v2 迁移 / 2026-06-14 schema 重写）：
 * - 从 `useCopilotAction.renderAndWaitForResponse`（v1，已 deprecated）迁移到 `useHumanInTheLoop`（v2）
 * - v1 的 renderAndWaitForResponse 在 CopilotKit 1.54+ 已 deprecated
 *   （源码注释：`@ts-expect-error -- renderAndWaitForResponse is deprecated`）
 *   桥接路径不再把 respond() 的 result 转发为新的 tool_result 发回后端
 * - v2 useHumanInTheLoop 用 Promise-based handler：
 *   handler() 返回挂起 Promise → respond() 解析它 → CopilotKit runtime 把 result 发回 backend
 * - v2 的 parameters 必须是 Standard Schema V1 兼容的 schema（Zod 3.24+ / Valibot v1+ / ArkType v2+）
 *   早期 v1 的 `Parameter[]` 数组会被 `createToolSchema → schemaToJsonSchema` 拒绝（读取
 *   `schema["~standard"].vendor` 时抛 "Cannot read properties of undefined"）
 *
 * 重要差异：
 * - v2 的 render 会被调用三次：inProgress（无 respond）→ executing（有 respond）→ complete（有 result 无 respond）
 * - fetch + respond 必须放在 useEffect 里（让 executing 状态持续 render，handler 的 Promise 才不会立即 resolve）
 */
const HttpRequestParams = z.object({
  method: z
    .enum(["GET", "POST", "PUT", "DELETE", "PATCH"])
    .describe("HTTP 方法"),
  url: z
    .string()
    .describe(
      "API 路径（相对路径如 /api/products 或完整 URL 如 http://example.com/foo）"
    ),
  // 注意：使用 "string" 而非 "object"，LLM 传入 JSON 字符串（如 '{"productId":"1"}'）
  params: z
    .string()
    .optional()
    .describe(
      '路径/查询参数，JSON 格式的键值对字符串，如 \'{"productId":"1"}\''
    ),
  body: z
    .string()
    .optional()
    .describe("请求体（仅 POST/PUT/PATCH，JSON 字符串）"),
});

/**
 * 后端地址
 *
 * 注意：使用 NEXT_PUBLIC_ 前缀使其在浏览器中可访问。
 * 与原 ConfirmDialogContainer.tsx 中硬编码的 'http://localhost:8080' 一致，
 * 但通过环境变量提供覆盖能力。
 */
const JAVA_BACKEND_BASE =
  typeof window !== "undefined"
    ? process.env.NEXT_PUBLIC_JAVA_BACKEND_URL || "http://localhost:8080"
    : "http://localhost:8080";

/**
 * HTTP 请求执行结果
 */
export interface HttpExecutionResult {
  success: boolean;
  status: number;
  body: string;
  error?: string;
  cancelled?: boolean;
}

/**
 * 执行 HTTP 请求
 *
 * 从原 ConfirmDialogContainer.executeHttpRequest 迁移，逻辑保持一致：
 * - 解析 params JSON 字符串
 * - 相对路径拼接 JAVA_BACKEND_BASE
 * - 从 localStorage 读取 auth token
 * - GET/HEAD 请求无 body
 */
async function executeHttpRequest(params: {
  method: string;
  url: string;
  params?: string;
  body?: string;
}): Promise<HttpExecutionResult> {
  const { method, url, params: paramsJson, body } = params;

  // 解析 params JSON 字符串
  let pathParams: Record<string, string> = {};
  if (paramsJson) {
    try {
      pathParams = JSON.parse(paramsJson);
    } catch (e) {
      console.warn(
        "[executeHttpRequest] Failed to parse params JSON:",
        paramsJson,
        e
      );
    }
  }

  // 解析 URL：绝对 URL 直接使用，相对路径拼接 Java 后端地址
  let fullUrl = url.startsWith("http")
    ? url
    : `${JAVA_BACKEND_BASE}${url.startsWith("/") ? "" : "/"}${url}`;

  // 拼接查询参数
  if (Object.keys(pathParams).length > 0) {
    fullUrl +=
      (fullUrl.includes("?") ? "&" : "?") +
      new URLSearchParams(pathParams).toString();
  }

  // 获取 auth token（与 ConfirmDialogContainer 保持一致）
  const token =
    typeof window !== "undefined"
      ? localStorage.getItem("auth_token")
      : null;

  const headers: Record<string, string> = {};
  // GET/HEAD 请求不需要 Content-Type
  const isGetOrHead = method === "GET" || method === "HEAD";
  if (!isGetOrHead) {
    headers["Content-Type"] = "application/json";
  }
  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }

  try {
    const resp = await fetch(fullUrl, {
      method,
      headers,
      // body 需要是 JSON 字符串
      body: body && !isGetOrHead ? body : undefined,
    });

    const responseBody = await resp.text();

    return {
      success: resp.ok,
      status: resp.status,
      body: responseBody,
    };
  } catch (e: any) {
    return {
      success: false,
      status: 0,
      body: "",
      error: e?.message || String(e),
    };
  }
}

/**
 * 内部状态机（用于确认对话框的本地交互）
 */
type DialogState = "pending" | "executing" | "cancelled";

/**
 * v2 useHumanInTheLoop 的 render 组件
 *
 * 关键设计点（基于 1.54.0 真实 ReactHumanInTheLoop 类型定义）：
 * - render 接收一个 *discriminated union*，由 `status` 字段区分三种状态：
 *   - inProgress: respond = undefined, args = Partial<T>，参数可能还没到齐
 *   - executing: respond = (result) => Promise<void>, args = T
 *   - complete: result = string, respond = undefined, args = T
 * - 必须让组件在 status 变化时保持 mount，fetch + respond 才能在 useEffect 里串行
 * - GET 请求在 executing 阶段自动 fetch + respond
 * - POST/PUT/DELETE/PATCH 在 executing 阶段显示确认对话框
 */
function HttpRequestRender(props: {
  name: string;
  description: string;
  args: any;
  status: "inProgress" | "executing" | "complete";
  result?: string;
  respond?: (result: unknown) => Promise<void>;
}) {
  const { args, status, result, respond } = props;

  // 调试日志：追踪状态转换
  console.log('[HttpRequestRender]', {
    status,
    args,
    hasRespond: typeof respond === 'function',
    resultLength: result?.length,
    timestamp: new Date().toISOString(),
  });

  // 1) inProgress：LLM 刚决定调工具，参数可能还没到齐
  if (status === "inProgress") {
    return (
      <div className="text-xs text-gray-500 italic my-2">
        准备调用 httpRequest...
      </div>
    );
  }

  // 2) complete：工具已返回 result
  if (status === "complete") {
    let parsed: HttpExecutionResult | null = null;
    try {
      parsed = result ? JSON.parse(result) : null;
    } catch {
      // 兼容直接传字符串的情况
    }
    const success = parsed?.success ?? true;
    const statusCode = parsed?.status ?? 200;
    return (
      <div
        className={`text-xs italic my-2 ${
          success ? "text-green-600" : "text-red-600"
        }`}
      >
        ✓ httpRequest 完成 ({statusCode})
        {parsed?.cancelled ? " — 用户已取消" : ""}
      </div>
    );
  }

  // 3) executing：handler 的 Promise 挂起中，等待 respond
  // 此时 status narrow 到 "executing"，respond 必为函数、args 必有值
  const isReadOnly = args.method === "GET" || args.method === "HEAD";

  if (isReadOnly) {
    return (
      <GetRequestProgress
        method={args.method}
        url={args.url}
        onComplete={(res) => respond?.(res)}
      />
    );
  }

  return (
    <HttpConfirmationDialog
      method={args.method}
      url={args.url}
      params={args.params}
      body={args.body}
      onConfirm={async () => {
        const res = await executeHttpRequest({
          method: args.method,
          url: args.url,
          params: args.params,
          body: args.body,
        });
        respond?.(res);
      }}
      onCancel={() =>
        respond?.({
          success: false,
          status: 0,
          body: "",
          cancelled: true,
          error: "用户取消了该操作",
        })
      }
    />
  );
}

/**
 * GET 请求的"执行中"组件
 *
 * v2 关键设计：必须让组件持续 render 直到 fetch 完成，否则 handler 的 Promise
 * 不会"等"异步 fetch 结束，CopilotKit 会把 respond() 视为立即完成，
 * 不会触发下一轮 /api/copilotkit 请求把 tool_result 投回后端。
 *
 * 用 useState + useEffect 模式：useEffect 启动 fetch，setDone(true) 让组件从
 * "执行中" 切到 "已完成"，再调 respond 把结果传出去。
 */
function GetRequestProgress({
  method,
  url,
  onComplete,
}: {
  method: string;
  url: string;
  onComplete: (result: HttpExecutionResult) => void;
}) {
  const [done, setDone] = React.useState(false);

  React.useEffect(() => {
    let cancelled = false;
    executeHttpRequest({ method, url }).then((result) => {
      if (cancelled) return;
      setDone(true);
      // 给一帧时间让 CopilotKit 看到 "complete" 状态再调用 respond
      setTimeout(() => onComplete(result), 50);
    });
    return () => {
      cancelled = true;
    };
  }, [method, url, onComplete]);

  if (done) {
    return (
      <div className="text-xs text-gray-500 italic my-2">
        已完成 {method} {url}
      </div>
    );
  }
  return (
    <div className="text-xs text-gray-500 italic my-2">
      正在执行 {method} {url} ...
    </div>
  );
}

/**
 * 注册 HTTP 请求工具的 Hook（v2 useHumanInTheLoop）
 *
 * 与原 v1 useCopilotAction.renderAndWaitForResponse 的关键区别：
 * - 不再提供 renderAndWaitForResponse 函数
 * - 提供 render 组件（React.ComponentType）
 * - fetch + respond 必须在 render 的 useEffect 里执行（handler 的 Promise 模式）
 * - v2 的 render 会被调用 3 次（inProgress → executing → complete），组件需处理所有状态
 */
export function useHttpRequestTool() {
  // 调试日志：追踪 processAgentResult 调用
  React.useEffect(() => {
    if (typeof window !== 'undefined') {
      (window as any).__DEBUG_PROCESS_AGENT_RESULT = true;
    }
  }, []);

  useHumanInTheLoop({
    name: "httpRequest",
    description:
      "发送 HTTP 请求调用 REST API，支持 GET/POST/PUT/DELETE/PATCH。会自动使用当前登录用户的 access token。写操作（POST/PUT/DELETE/PATCH）会暂停并显示确认对话框等待用户确认。",
    parameters: HttpRequestParams as any,
    render: HttpRequestRender as any,
  });
}

/**
 * 确认对话框组件
 *
 * 从原 ConfirmDialogContainer 简化而来，专注于显示确认 UI。
 * 写操作在 v2 的 executing 阶段渲染此组件。
 */
function HttpConfirmationDialog({
  method,
  url,
  params: paramsJson,
  body,
  onConfirm,
  onCancel,
}: {
  method: string;
  url: string;
  params?: string;
  body?: string;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const [isExecuting, setIsExecuting] = React.useState(false);

  // 解析 params JSON 字符串用于显示
  let params: Record<string, string> | undefined;
  if (paramsJson) {
    try {
      params = JSON.parse(paramsJson);
    } catch {
      // 解析失败，params 保持 undefined，将显示原始字符串
    }
  }

  const handleConfirm = async () => {
    if (isExecuting) return;
    setIsExecuting(true);
    try {
      await onConfirm();
    } finally {
      // 不需要 setIsExecuting(false)，因为 respond() 之后会切到 complete 状态
    }
  };

  return (
    <div className="bg-white dark:bg-gray-800 rounded-lg shadow-xl border border-gray-200 dark:border-gray-700 p-4 max-w-md my-2 pointer-events-auto">
      {/* 标题 */}
      <div className="flex items-center gap-2 mb-3">
        <span className="text-2xl">⚠️</span>
        <h3 className="text-lg font-semibold text-gray-900 dark:text-white">
          操作确认
        </h3>
      </div>

      {/* 请求详情 */}
      <div className="bg-gray-50 dark:bg-gray-900 rounded-lg p-3 mb-4 text-sm">
        <div className="flex items-center gap-2 mb-2">
          <span
            className={[
              "px-2 py-0.5 rounded text-xs font-medium uppercase",
              method === "POST"
                ? "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200"
                : "",
              method === "PUT"
                ? "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200"
                : "",
              method === "DELETE"
                ? "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200"
                : "",
              method === "PATCH"
                ? "bg-purple-100 text-purple-800 dark:bg-purple-900 dark:text-purple-200"
                : "",
              !["POST", "PUT", "DELETE", "PATCH"].includes(method)
                ? "bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200"
                : "",
            ]
              .filter(Boolean)
              .join(" ")}
          >
            {method}
          </span>
          <code className="text-gray-800 dark:text-gray-200 font-mono text-xs break-all">
            {url}
          </code>
        </div>

        {params && Object.keys(params).length > 0 ? (
          <div className="text-xs text-gray-500 dark:text-gray-400 mt-1">
            参数：{new URLSearchParams(params).toString()}
          </div>
        ) : paramsJson ? (
          <div className="text-xs text-gray-500 dark:text-gray-400 mt-1">
            参数：{paramsJson}
          </div>
        ) : null}

        {body && (
          <details className="mt-2">
            <summary className="cursor-pointer text-gray-500 dark:text-gray-400 text-xs hover:text-gray-700 dark:hover:text-gray-200">
              请求体
            </summary>
            <pre className="mt-1 text-xs bg-gray-100 dark:bg-gray-800 p-2 rounded overflow-x-auto max-h-32">
              {body}
            </pre>
          </details>
        )}
      </div>

      {/* 操作按钮 */}
      <div className="flex justify-end gap-2">
        <button
          onClick={onCancel}
          disabled={isExecuting}
          className="px-4 py-2 rounded-lg text-sm font-medium bg-gray-100 dark:bg-gray-700 text-gray-700 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-gray-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          取消
        </button>
        <button
          onClick={handleConfirm}
          disabled={isExecuting}
          className="px-4 py-2 rounded-lg text-sm font-medium bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center gap-2"
        >
          {isExecuting && (
            <svg
              className="animate-spin h-4 w-4"
              viewBox="0 0 24 24"
              fill="none"
            >
              <circle
                className="opacity-25"
                cx="12"
                cy="12"
                r="10"
                stroke="currentColor"
                strokeWidth="4"
              />
              <path
                className="opacity-75"
                fill="currentColor"
                d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
              />
            </svg>
          )}
          {isExecuting ? "执行中..." : "确认执行"}
        </button>
      </div>
    </div>
  );
}
