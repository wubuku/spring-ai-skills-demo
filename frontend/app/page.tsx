"use client";

import React from "react";
import { CopilotPopup } from "@copilotkit/react-ui";
import {
  AssistantMessage as DefaultAssistantMessage,
} from "@copilotkit/react-ui";
import { useCopilotContext } from "@copilotkit/react-core";
import { ConfirmDialogContainer, HttpRequestMeta } from "@/components/ConfirmDialogContainer";
import { AuthProvider, useAuth } from "@/components/AuthProvider";

/**
 * 模块级状态缓存：存储已确认的 requestMeta，避免重复显示
 */
const confirmedRequests = new Set<string>();

/**
 * 从消息内容中提取 http-request 代码块
 * 支持两种格式：
 * 1. 标准格式：```http-request\n{...}\n```
 * 2. 不完整格式（LLM 可能生成）：```http-request{...}（缺少结束 ```）
 */
function extractHttpRequestMeta(content: string): {
  cleanedContent: string;
  requestMeta?: HttpRequestMeta;
} {
  // 优先尝试标准格式（有闭合的 ```）
  let pattern = /```http-request\s*\n?([\s\S]*?)```/g;
  let matches = [...content.matchAll(pattern)];

  // 如果没有匹配到，尝试不完整格式（没有闭合的 ```）
  // 匹配 ```http-request 后面跟着 JSON 内容，直到行尾
  if (matches.length === 0) {
    pattern = /```http-request\s*\n?(\{[\s\S]*)/g;
    matches = [...content.matchAll(pattern)];
  }

  if (matches.length === 0) {
    return { cleanedContent: content };
  }

  const lastMatch = matches[matches.length - 1];
  let jsonContent = (lastMatch[1] || '').trim();

  // 尝试修复不完整的 JSON（如果没有闭合的 }）
  if (jsonContent && !jsonContent.endsWith('}')) {
    // 找到最后一个 } 并截断
    const lastBraceIndex = jsonContent.lastIndexOf('}');
    if (lastBraceIndex > 0) {
      jsonContent = jsonContent.substring(0, lastBraceIndex + 1);
    }
  }

  let requestMeta: HttpRequestMeta | undefined;

  if (jsonContent) {
    try {
      const raw = JSON.parse(jsonContent);
      requestMeta = {
        method: raw.method || 'POST',
        url: raw.url || '',
        params: raw.params || raw.queryParams || {},
        body: raw.body,
        headers: raw.headers,
      };
    } catch (e) {
      console.log('[extractHttpRequestMeta] JSON parse error:', e);
    }
  }

  // 移除 http-request 代码块（支持完整和不完整格式）
  let cleanedContent = content
    .replace(/```http-request[\s\S]*?```/g, '')  // 完整格式
    .replace(/```http-request[\s\S]*/g, '')      // 不完整格式（没有闭合 ```）
    .trim();

  return { cleanedContent, requestMeta };
}

/**
 * 生成唯一的请求 key（包含参数）
 */
function getRequestKey(requestMeta: HttpRequestMeta | undefined): string | null {
  if (!requestMeta) return null;
  const paramsStr = requestMeta.params ? JSON.stringify(requestMeta.params) : '';
  return `${requestMeta.method}-${requestMeta.url}-${paramsStr}`;
}

/**
 * 自定义 AssistantMessage - 使用 DefaultAssistantMessage 渲染消息
 * 通过 markdownTagRenderers 的 pre 标签拦截来显示确认对话框
 */
function CustomAssistantMessage(props: any) {
  const { isLoading } = useCopilotContext();
  const messageId = props.message?.id;

  const content = props.message?.content || props.message?.text || '';

  // 提取 requestMeta
  const { cleanedContent, requestMeta } = extractHttpRequestMeta(content);

  // 始终显示确认对话框
  // 每次用户发送新消息时，如果有 http-request，都会显示确认对话框
  // 这是更安全的做法，避免用户错过确认重要操作
  const requestKey = requestMeta ? getRequestKey(requestMeta) : null;

  // 只有在流完成后、有 requestMeta 时才显示确认对话框
  // 不缓存确认状态，确保每次都让用户确认（安全优先）
  const showConfirm = !isLoading && requestMeta;

  // 创建标准化消息
  const normalizedMessage = props.message
    ? { ...props.message, content: cleanedContent }
    : props.message;

  // 创建一个 ref 来存储确认对话框的回调
  const confirmRef = React.useRef<{
    resolve: (confirmed: boolean) => void;
  } | null>(null);

  // 当用户点击确认时的处理
  const handleConfirm = React.useCallback(() => {
    if (requestKey) {
      confirmedRequests.add(requestKey);
    }
    if (confirmRef.current) {
      confirmRef.current.resolve(true);
      confirmRef.current = null;
    }
  }, [requestKey]);

  // 当用户点击取消时的处理
  const handleCancel = React.useCallback(() => {
    if (confirmRef.current) {
      confirmRef.current.resolve(false);
      confirmRef.current = null;
    }
  }, []);

  // 创建一个 Promise 来等待用户确认
  // 这里我们不使用阻塞，而是让 ConfirmDialogContainer 自己处理

  return (
    <>
      <DefaultAssistantMessage
        {...props}
        message={normalizedMessage}
      />
      {showConfirm && (
        <div className="mt-2">
          <ConfirmDialogContainer
            key={requestKey}
            requestMeta={requestMeta}
          />
        </div>
      )}
    </>
  );
}

function AuthBar() {
  const { token, username, login, logout } = useAuth();
  const [showLogin, setShowLogin] = React.useState(false);

  return (
    <>
      <div className="fixed top-4 right-4 z-40 flex items-center gap-3">
        {token ? (
          <>
            <span className="text-sm text-gray-700 dark:text-gray-300">
              欢迎，<strong>{username}</strong>
            </span>
            <button
              onClick={logout}
              className="bg-gray-200 dark:bg-gray-700 text-gray-800 dark:text-white px-4 py-2 rounded-md hover:bg-gray-300 dark:hover:bg-gray-600"
            >
              登出
            </button>
          </>
        ) : (
          <button
            onClick={() => setShowLogin(true)}
            className="bg-blue-600 text-white px-4 py-2 rounded-md hover:bg-blue-700"
          >
            登录
          </button>
        )}
      </div>
      {showLogin && (
        <LoginModalWrapper onClose={() => setShowLogin(false)} />
      )}
    </>
  );
}

function LoginModalWrapper({ onClose }: { onClose: () => void }) {
  const { login } = useAuth();
  const [username, setUsername] = React.useState("");
  const [password, setPassword] = React.useState("");
  const [error, setError] = React.useState("");

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    const success = await login(username, password);
    if (success) {
      onClose();
    } else {
      setError("用户名或密码错误");
    }
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white dark:bg-gray-800 rounded-lg p-6 w-96 shadow-xl">
        <h2 className="text-2xl font-bold mb-4 text-gray-800 dark:text-white">登录</h2>
        <form onSubmit={handleSubmit}>
          <div className="mb-4">
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">用户名</label>
            <input
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-md dark:bg-gray-700 dark:text-white"
              placeholder="user1 或 user2 或 admin"
              required
            />
          </div>
          <div className="mb-4">
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">密码</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-md dark:bg-gray-700 dark:text-white"
              placeholder="password1 或 password2 或 admin123"
              required
            />
          </div>
          {error && <p className="text-red-500 text-sm mb-4">{error}</p>}
          <div className="flex gap-3">
            <button type="submit" className="flex-1 bg-blue-600 text-white py-2 px-4 rounded-md hover:bg-blue-700">登录</button>
            <button type="button" onClick={onClose} className="flex-1 bg-gray-300 dark:bg-gray-600 text-gray-800 dark:text-white py-2 px-4 rounded-md hover:bg-gray-400">取消</button>
          </div>
        </form>
        <div className="mt-4 text-sm text-gray-500 dark:text-gray-400">
          <p>Demo 测试用户：</p>
          <ul className="mt-1 space-y-1">
            <li>user1 / password1 (张三)</li>
            <li>user2 / password2 (李四)</li>
            <li>admin / admin123 (管理员)</li>
          </ul>
        </div>
      </div>
    </div>
  );
}

export default function Home() {
  return (
    <AuthProvider>
      <HomeContent />
    </AuthProvider>
  );
}

function HomeContent() {
  const { token, username } = useAuth();
  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-100 dark:from-gray-900 dark:to-gray-800">
      <AuthBar />
      <main className="container mx-auto px-4 py-8">
        {/* Header */}
        <div className="text-center mb-12">
          <h1 className="text-4xl font-bold text-gray-800 dark:text-white mb-4">
            企业智能助手
          </h1>
          <p className="text-lg text-gray-600 dark:text-gray-300">
            基于 CopilotKit 的现代化智能体界面
            {token && <span className="ml-2 text-green-600">（已登录: {username}）</span>}
          </p>
        </div>

        {/* Feature Cards */}
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 mb-12">
          <FeatureCard
            icon="💬"
            title="智能对话"
            description="自然语言交互，理解您的业务需求"
          />
          <FeatureCard
            icon="🔧"
            title="技能加载"
            description="按需加载技能模块，执行复杂任务"
          />
          <FeatureCard
            icon="🔐"
            title="安全确认"
            description="敏感操作需要用户确认，保障安全"
          />
          <FeatureCard
            icon="📊"
            title="数据查询"
            description="快速查询商品、订单、员工等信息"
          />
          <FeatureCard
            icon="⚡"
            title="流式响应"
            description="实时流式输出，响应更流畅"
          />
          <FeatureCard
            icon="🎯"
            title="上下文记忆"
            description="记住对话历史，持续交互"
          />
        </div>

        {/* Usage Instructions */}
        <div className="bg-white dark:bg-gray-800 rounded-lg shadow-lg p-6 mb-8">
          <h2 className="text-2xl font-semibold text-gray-800 dark:text-white mb-4">
            使用指南
          </h2>
          <ul className="space-y-3 text-gray-600 dark:text-gray-300">
            <li className="flex items-start">
              <span className="text-green-500 mr-2">✓</span>
              <span>点击右下角的聊天图标打开对话框</span>
            </li>
            <li className="flex items-start">
              <span className="text-green-500 mr-2">✓</span>
              <span>输入您的问题或请求，例如："搜索商品"、"查询订单"、"帮助我更新宠物信息"</span>
            </li>
            <li className="flex items-start">
              <span className="text-green-500 mr-2">✓</span>
              <span>助手会根据需要加载相应的技能模块</span>
            </li>
            <li className="flex items-start">
              <span className="text-green-500 mr-2">✓</span>
              <span>对于敏感操作（如删除、修改），助手会请求您的确认</span>
            </li>
          </ul>
        </div>

        {/* CopilotKit Popup Component */}
        <CopilotPopup
          instructions="你是企业智能助手，帮助员工解答业务问题、查询数据、执行操作。"
          labels={{
            title: "企业智能助手",
            initial: "你好！我是企业智能助手，有什么可以帮助你的吗？",
            placeholder: "输入你的问题...",
          }}
          AssistantMessage={CustomAssistantMessage}
          markdownTagRenderers={{
            // 表格渲染，确保 Markdown 表格正确显示
            table: ({ children, ...props }: any) => (
              <div className="overflow-x-auto my-4">
                <table className="min-w-full border-collapse border border-gray-300 dark:border-gray-600" {...props}>
                  {children}
                </table>
              </div>
            ),
            thead: ({ children, ...props }: any) => (
              <thead className="bg-gray-100 dark:bg-gray-700" {...props}>
                {children}
              </thead>
            ),
            tbody: ({ children, ...props }: any) => (
              <tbody className="divide-y divide-gray-200 dark:divide-gray-700" {...props}>
                {children}
              </tbody>
            ),
            tr: ({ children, ...props }: any) => (
              <tr className="hover:bg-gray-50 dark:hover:bg-gray-800" {...props}>
                {children}
              </tr>
            ),
            th: ({ children, ...props }: any) => (
              <th className="border border-gray-300 dark:border-gray-600 px-4 py-2 text-left font-semibold text-gray-900 dark:text-gray-100" {...props}>
                {children}
              </th>
            ),
            td: ({ children, ...props }: any) => (
              <td className="border border-gray-300 dark:border-gray-600 px-4 py-2 text-gray-700 dark:text-gray-300" {...props}>
                {children}
              </td>
            ),
          }}
        />
      </main>
    </div>
  );
}

function FeatureCard({
  icon,
  title,
  description,
}: {
  icon: string;
  title: string;
  description: string;
}) {
  return (
    <div className="bg-white dark:bg-gray-800 rounded-lg shadow-md p-6 hover:shadow-lg transition-shadow">
      <div className="text-4xl mb-3">{icon}</div>
      <h3 className="text-xl font-semibold text-gray-800 dark:text-white mb-2">
        {title}
      </h3>
      <p className="text-gray-600 dark:text-gray-300">{description}</p>
    </div>
  );
}
