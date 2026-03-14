"use client";

import React, { useState, useCallback, useEffect } from 'react';

export interface HttpRequestMeta {
  method: string;
  url: string;
  headers?: Record<string, string>;
  body?: any;
  params?: Record<string, string>;
  description?: string;
}

export interface HttpExecutionResult {
  success: boolean;
  message: string;
  result?: string;
  error?: string;
  cancelled?: boolean;
}

const ALLOWED_DOMAINS = [
  'localhost:8080',
  '127.0.0.1:8080',
  'petstore.swagger.io',
];

function isUrlAllowed(url: string): boolean {
  try {
    const parsed = new URL(url, window.location.origin);
    return ALLOWED_DOMAINS.some(d => parsed.host === d || parsed.host.endsWith('.' + d));
  } catch {
    return false;
  }
}

const BACKEND_BASE = 'http://localhost:8080';

async function executeHttpRequest(meta: HttpRequestMeta): Promise<string> {
  if (!isUrlAllowed(meta.url)) {
    throw new Error(`URL 不在允许的域名白名单中: ${meta.url}`);
  }

  let url = meta.url;
  // 支持 queryParams 和 params 两种字段名
  const queryParams = meta.params || meta.queryParams || {};
  if (Object.keys(queryParams).length > 0) {
    url += '?' + new URLSearchParams(queryParams).toString();
  }

  const bodyStr = meta.body
    ? (typeof meta.body === 'string' ? meta.body : JSON.stringify(meta.body))
    : undefined;

  // 获取当前用户的认证 Token
  const token = localStorage.getItem('auth_token');
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...meta.headers };
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const resp = await fetch(url, {
    method: meta.method,
    headers,
    body: bodyStr,
  });
  const responseText = await resp.text();

  // 调用后端 explain-result API（使用完整 URL，避免请求到 Next.js 服务器）
  try {
    const explainResp = await fetch(`${BACKEND_BASE}/api/explain-result`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        method: meta.method,
        url: meta.url,
        queryParams: meta.params || {},
        statusCode: resp.status,
        responseBody: responseText,
      }),
    });
    if (explainResp.ok) return await explainResp.text();
  } catch {
    // 解释失败，返回原始响应
  }

  return responseText;
}

type Stage = 'pending' | 'executing' | 'completed' | 'cancelled';

interface CachedState {
  stage: Stage;
  result: string | null;
  error: string | null;
  /** 保存 in-flight 的 Promise，供组件重挂载后接回 */
  promise?: Promise<string>;
}

/**
 * 模块级状态缓存：key = "METHOD-url"
 * 组件被 CopilotKit 重新渲染卸载/挂载时，可从此处恢复状态并重连 Promise
 */
const confirmStateCache = new Map<string, CachedState>();

interface ConfirmDialogContainerProps {
  requestMeta: HttpRequestMeta;
  description?: string;
  respond?: (result: HttpExecutionResult) => void;
}

/**
 * 确认对话框组件（自包含状态管理 + 模块级缓存防卸载丢状态）
 *
 * 通过 markdownTagRenderers 的 pre 覆盖渲染，检测到 language-http-request
 * 代码块时替代普通代码块展示。
 */
export function ConfirmDialogContainer({
  requestMeta,
  description,
  respond,
}: ConfirmDialogContainerProps) {
  const stableKey = `${requestMeta.method}-${requestMeta.url}`;
  const cached = confirmStateCache.get(stableKey);

  const [stage, setStage] = useState<Stage>(cached?.stage ?? 'pending');
  const [result, setResult] = useState<string | null>(cached?.result ?? null);
  const [error, setError] = useState<string | null>(cached?.error ?? null);

  // 若挂载时发现缓存状态是 'executing'，说明组件在请求进行中被卸载又重挂载了；
  // 重新接回已保存的 Promise，避免永久停在"执行中..."或需要用户再次点击
  useEffect(() => {
    const c = confirmStateCache.get(stableKey);
    if (c?.stage === 'executing' && c?.promise) {
      c.promise.then(
        (res) => {
          setResult(res);
          setStage('completed');
          confirmStateCache.set(stableKey, { stage: 'completed', result: res, error: null });
          respond?.({ success: true, message: '操作执行成功', result: res });
        },
        (e: any) => {
          const msg = e?.message ?? '请求失败';
          setError(msg);
          setStage('completed');
          confirmStateCache.set(stableKey, { stage: 'completed', result: null, error: msg });
          respond?.({ success: false, message: msg, error: msg });
        }
      );
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []); // 仅在挂载时运行一次

  const handleConfirm = useCallback(async () => {
    setStage('executing');
    setError(null);

    const promise = executeHttpRequest(requestMeta);
    // 立即写入缓存，包含 Promise 引用
    confirmStateCache.set(stableKey, { stage: 'executing', result: null, error: null, promise });

    try {
      const res = await promise;
      setResult(res);
      setStage('completed');
      confirmStateCache.set(stableKey, { stage: 'completed', result: res, error: null });
      respond?.({ success: true, message: '操作执行成功', result: res });
    } catch (e: any) {
      const msg = e.message || '请求失败';
      setError(msg);
      setStage('completed');
      confirmStateCache.set(stableKey, { stage: 'completed', result: null, error: msg });
      respond?.({ success: false, message: msg, error: msg });
    }
  }, [requestMeta, respond, stableKey]);

  const handleCancel = useCallback(() => {
    setStage('cancelled');
    confirmStateCache.set(stableKey, { stage: 'cancelled', result: null, error: null });
    respond?.({ success: false, message: '已取消该操作', cancelled: true });
  }, [respond, stableKey]);

  if (stage === 'cancelled') {
    return (
      <div className="text-sm text-gray-500 p-2 italic my-2">
        已取消该操作。
      </div>
    );
  }

  if (stage === 'completed') {
    return (
      <div className="rounded-lg p-3 my-2 border text-sm bg-green-50 dark:bg-green-900/20 border-green-200 dark:border-green-800">
        {error ? (
          <>
            <span className="font-medium text-red-600 dark:text-red-400">❌ 操作失败：</span>
            <span className="text-gray-700 dark:text-gray-300">{error}</span>
          </>
        ) : (
          <>
            <div className="font-medium text-green-600 dark:text-green-400 mb-1">✅ 操作结果：</div>
            <div className="text-gray-700 dark:text-gray-300 whitespace-pre-wrap">{result}</div>
          </>
        )}
      </div>
    );
  }

  return (
    <div className="bg-white dark:bg-gray-800 rounded-lg shadow-xl border border-gray-200 dark:border-gray-700 p-4 max-w-md my-2 pointer-events-auto">
      {/* 标题 */}
      <div className="flex items-center gap-2 mb-3">
        <span className="text-2xl">⚠️</span>
        <h3 className="text-lg font-semibold text-gray-900 dark:text-white">操作确认</h3>
      </div>

      {/* 请求详情 */}
      <div className="bg-gray-50 dark:bg-gray-900 rounded-lg p-3 mb-4 text-sm">
        {(description || requestMeta.description) && (
          <div className="text-gray-700 dark:text-gray-300 mb-3">
            {description || requestMeta.description}
          </div>
        )}
        <div className="flex items-center gap-2 mb-2">
          <span className={[
            'px-2 py-0.5 rounded text-xs font-medium uppercase',
            requestMeta.method === 'POST' ? 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200' : '',
            requestMeta.method === 'PUT' ? 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200' : '',
            requestMeta.method === 'DELETE' ? 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200' : '',
            !['POST', 'PUT', 'DELETE'].includes(requestMeta.method) ? 'bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200' : '',
          ].filter(Boolean).join(' ')}>
            {requestMeta.method}
          </span>
          <code className="text-gray-800 dark:text-gray-200 font-mono text-xs break-all">
            {requestMeta.url}
          </code>
        </div>
        {requestMeta.params && Object.keys(requestMeta.params).length > 0 && (
          <div className="text-xs text-gray-500 dark:text-gray-400 mt-1">
            参数：{new URLSearchParams(requestMeta.params).toString()}
          </div>
        )}
        {requestMeta.body && (
          <details className="mt-2">
            <summary className="cursor-pointer text-gray-500 dark:text-gray-400 text-xs hover:text-gray-700 dark:hover:text-gray-200">
              请求体
            </summary>
            <pre className="mt-1 text-xs bg-gray-100 dark:bg-gray-800 p-2 rounded overflow-x-auto max-h-32">
              {typeof requestMeta.body === 'string'
                ? requestMeta.body
                : JSON.stringify(requestMeta.body, null, 2)}
            </pre>
          </details>
        )}
      </div>

      {/* 操作按钮 */}
      <div className="flex justify-end gap-2">
        <button
          onClick={handleCancel}
          disabled={stage === 'executing'}
          className="px-4 py-2 rounded-lg text-sm font-medium bg-gray-100 dark:bg-gray-700 text-gray-700 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-gray-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          取消
        </button>
        <button
          onClick={handleConfirm}
          disabled={stage === 'executing'}
          className="px-4 py-2 rounded-lg text-sm font-medium bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center gap-2"
        >
          {stage === 'executing' && (
            <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24" fill="none">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
            </svg>
          )}
          {stage === 'executing' ? '执行中...' : '确认执行'}
        </button>
      </div>
    </div>
  );
}
