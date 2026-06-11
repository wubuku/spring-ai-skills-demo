"use client";

import React from "react";
import { useCopilotAction } from "@copilotkit/react-core";

/**
 * HTTP 请求参数 Schema
 * 注意：使用 v1 格式的 Parameter[]，与 useCopilotAction 兼容
 *
 * 重构说明：
 * - 这是从 `frontend/components/ConfirmDialogContainer.tsx` 中的 `HttpRequestMeta` 接口迁移而来
 * - 从对象类型 (`params: Record<string, string>`) 改为字符串类型（JSON 字符串）
 *   因为 CopilotKit 的工具参数通常是基本类型，LLM 也更容易生成 JSON 字符串
 */
const HttpRequestParams = [
  {
    name: "method",
    type: "string",
    enum: ["GET", "POST", "PUT", "DELETE", "PATCH"],
    description: "HTTP 方法",
    required: true,
  },
  {
    name: "url",
    type: "string",
    description: "API 路径（相对路径如 /api/products 或完整 URL）",
    required: true,
  },
  {
    name: "params",
    type: "string", // 注意：使用 "string" 而非 "object"，LLM 传入 JSON 字符串
    description:
      "路径/查询参数，JSON 格式的键值对字符串，如 '{\"productId\":\"1\"}'",
    required: false,
  },
  {
    name: "body",
    type: "string",
    description: "请求体（仅 POST/PUT/PATCH，JSON 字符串）",
    required: false,
  },
] as const;

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
 * 注册 HTTP 请求工具的 Hook
 *
 * 使用 renderAndWaitForResponse 实现用户确认模式。
 * 这是 CopilotKit 原生机制，不再需要解析 http-request 代码块。
 */
export function useHttpRequestTool() {
  useCopilotAction({
    name: "httpRequest",
    description:
      "发送 HTTP 请求调用 REST API，支持 GET/POST/PUT/DELETE/PATCH。会自动使用当前登录用户的 access token。写操作（POST/PUT/DELETE/PATCH）会暂停并显示确认对话框等待用户确认。",
    parameters: HttpRequestParams as any,
    renderAndWaitForResponse: ({ args, status, respond }: any) => {
      // GET 请求直接执行（无需确认）
      const isReadOnly = args.method === "GET" || args.method === "HEAD";
      if (isReadOnly) {
        // 异步执行后返回结果
        executeHttpRequest({
          method: args.method,
          url: args.url,
          params: args.params,
          body: args.body,
        }).then((result) => {
          respond?.(result);
        });
        return (
          <div className="text-xs text-gray-500 italic my-2">
            正在执行 {args.method} {args.url} ...
          </div>
        );
      }

      // 渲染确认对话框（POST/PUT/DELETE/PATCH）
      return (
        <HttpConfirmationDialog
          method={args.method}
          url={args.url}
          params={args.params}
          body={args.body}
          isExecuting={status === "executing"}
          onConfirm={async () => {
            const result = await executeHttpRequest({
              method: args.method,
              url: args.url,
              params: args.params,
              body: args.body,
            });
            respond?.(result);
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
    },
  });
}

/**
 * 确认对话框组件
 *
 * 从原 ConfirmDialogContainer 简化而来，专注于显示确认 UI。
 * 状态管理由 useHttpRequestTool 内部处理（status: executing | complete）。
 */
function HttpConfirmationDialog({
  method,
  url,
  params: paramsJson,
  body,
  isExecuting,
  onConfirm,
  onCancel,
}: {
  method: string;
  url: string;
  params?: string;
  body?: string;
  isExecuting: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  // 解析 params JSON 字符串用于显示
  let params: Record<string, string> | undefined;
  if (paramsJson) {
    try {
      params = JSON.parse(paramsJson);
    } catch {
      // 解析失败，params 保持 undefined，将显示原始字符串
    }
  }

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
          onClick={onConfirm}
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
