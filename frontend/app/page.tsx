"use client";

import React from "react";
import { CopilotPopup } from "@copilotkit/react-core/v2";
import { useHttpRequestTool } from "@/hooks/useHttpRequestTool";
import { AuthProvider, useAuth } from "@/components/AuthProvider";
import { CopilotChatAssistantMessage } from "@copilotkit/react-core/v2";

/**
 * HTTP 请求工具 Provider
 *
 * 在 CopilotKit 子树中注册 `httpRequest` 工具（v2 useHumanInTheLoop 版本）。
 * - GET 请求自动执行（无确认）
 * - POST/PUT/DELETE/PATCH 请求显示确认对话框
 *
 * 重构说明（2026-06-13 B1 — v2 重构）：
 * - 从 v1 `useCopilotAction.renderAndWaitForResponse`（已 deprecated）迁移到 v2 `useHumanInTheLoop`
 * - v2 的 render 会被调用 3 次：inProgress → executing → complete
 * - 由 v2 CopilotKitProvider（在 components/CopilotProvider.tsx 中）创建 v2 context
 */
function HttpRequestToolProvider({ children }: { children: React.ReactNode }) {
  useHttpRequestTool();
  return <>{children}</>;
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

        {/* CopilotKit v2 Popup Component - 注册 httpRequest 工具（v2 useHumanInTheLoop） */}
        <HttpRequestToolProvider>
          <CopilotPopup
            defaultOpen={false}
            labels={{
              modalHeaderTitle: "企业智能助手",
              welcomeMessageText: "你好！我是企业智能助手，有什么可以帮助你的吗？",
              chatInputPlaceholder: "输入你的问题...",
            }}
            messageView={{
              // v2 messageView 插槽：只替换 Markdown 渲染器，保留默认消息壳和工具调用渲染。
              assistantMessage: {
                markdownRenderer: MarkdownRenderer as any,
              },
            }}
          />
        </HttpRequestToolProvider>
      </main>
    </div>
  );
}

/**
 * 自定义 Markdown 渲染器（v2 Streamdown + 自定义 components）
 *
 * 等价于 v1 的 markdownTagRenderers，但通过 Streamdown 的 `components` prop 传入。
 */
const MarkdownRenderer: React.FC<
  React.ComponentProps<typeof CopilotChatAssistantMessage.MarkdownRenderer>
> = ({ content, ...rest }) => {
  // 动态 import Streamdown 以避免 SSR 问题
  const [Streamdown, setStreamdown] = React.useState<any>(null);
  const sanitizedContent = React.useMemo(
    () => sanitizeAssistantContent(content),
    [content]
  );

  React.useEffect(() => {
    let cancelled = false;
    import("streamdown").then((mod) => {
      if (!cancelled) setStreamdown(() => mod.Streamdown);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  if (!Streamdown) {
    // SSR / loading fallback：直接渲染纯文本
    return <div className="whitespace-pre-wrap">{sanitizedContent}</div>;
  }

  return (
    <Streamdown components={markdownComponents} {...rest}>
      {sanitizedContent}
    </Streamdown>
  );
};

function sanitizeAssistantContent(content: string) {
  return stripLeakedPlanningLines((content || "")
    .replace(/\[\s*TOOL_CALL\s*\][\s\S]*?\[\s*\/\s*TOOL_CALL\s*\]/gi, "")
    .replace(
      /<\s*(parameter|invoke|tool_call|function_calls|antml_call)\b[^>]*>[\s\S]*?<\s*\/\s*\1\s*>/gi,
      ""
    )
    .replace(/<\s*\/\s*(parameter|invoke|tool_call|function_calls|antml_call)\s*>/gi, ""));
}

function stripLeakedPlanningLines(content: string) {
  // 中文规划文本模式
  const planningLinePattern =
    /^(用户想要|用户希望|用户问|用户再次|用户就|根据之前的对话|从对话记忆中|这应该就是|现在我需要|我已经加载了|我已经知道|我需要直接|让我|接下来我|根据工具返回的结果|我刚才已经|我应该|我来|用户想知道|我已经通过|好的|首先|接下来|然后我|现在让我|我来看看|让我来|我将|我先)/;

  // English planning patterns (including non-bold lines starting with common planning verbs)
  const englishPlanningPattern =
    /^(\*\*\s*)?(Checking|Reviewing|Analyzing|Processing|I'm|I've|Looking|Examining|Verifying|Confirming|Preparing|Waiting|Now I|Let me|I need|I'll|I am|I will|I think|I should|First,|Next,|Then,|Based on|According to|The user|It seems|It appears|I notice|I see|I understand)\b/i;

  // Bold markdown headers that are planning meta-narrative
  const metaHeaderPattern = /^\*\*[A-Z][a-z]+(ing|ed|tion)\b.*\*\*$/;

  // [TOOL_CALL]...[/TOOL_CALL] 伪文本工具调用块
  const fakeToolCallPattern = /^\[TOOL_CALL\]|^\[\/TOOL_CALL\]|^\{tool\s*=>/;

  // 过滤 think 标签内的内容（后端未折叠的场景）
  let result = content;

  return result
    .split(/\r?\n/)
    .filter((line) => {
      const trimmed = line.trim();
      if (!trimmed) return true;
      return (
        !planningLinePattern.test(trimmed) &&
        !englishPlanningPattern.test(trimmed) &&
        !metaHeaderPattern.test(trimmed) &&
        !fakeToolCallPattern.test(trimmed)
      );
    })
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

/**
 * Streamdown components（等价于 v1 markdownTagRenderers）
 *
 * 覆盖：
 * - table/thead/tbody/tr/th/td 表格样式
 * - think 标签 → 可折叠区域
 * - p 标签 → div（避免 React 19 p-in-details 警告）
 */
const markdownComponents = {
  table: ({ children, ...props }: any) => (
    <div className="overflow-x-auto my-4">
      <table
        className="min-w-full border-collapse border border-gray-300 dark:border-gray-600"
        {...props}
      >
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
    <th
      className="border border-gray-300 dark:border-gray-600 px-4 py-2 text-left font-semibold text-gray-900 dark:text-gray-100"
      {...props}
    >
      {children}
    </th>
  ),
  td: ({ children, ...props }: any) => (
    <td
      className="border border-gray-300 dark:border-gray-600 px-4 py-2 text-gray-700 dark:text-gray-300"
      {...props}
    >
      {children}
    </td>
  ),
  // 推理模型（MiniMax-M3 等）的 <think> 标签渲染为可折叠区域
  // 后端 StreamingTagFilter 已放行 <think> 标签，让前端做折叠显示
  // 默认折叠，避免推理过程抢占最终答案；用户需要时可展开。
  // 注意：HTML5 规范禁止 <p> 作为 <details> 的直接子元素
  // （markdown 解析器会把多行内容包成 <p>），所以这里把 children 用
  // 纯 div 包裹后再渲染
  think: ({ children, ...props }: any) => (
    <details
      className="my-2 rounded-md border border-gray-200 dark:border-gray-600 bg-gray-50 dark:bg-gray-800/50 text-sm"
      {...props}
    >
      <summary className="cursor-pointer px-3 py-1.5 text-xs font-medium text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 select-none">
        思考过程
      </summary>
      <div className="think-content px-3 py-2 text-gray-600 dark:text-gray-300 whitespace-pre-wrap text-xs leading-relaxed">
        {children}
      </div>
    </details>
  ),
  // 兼容：避免 React 19 的 p-in-details 嵌套警告
  // 把 <p> 重写为 <div class="copilotKitMarkdownElement">，让 block-level
  // 内容在 <details> 内也是合法 HTML
  p: ({ children, ...props }: any) => (
    <div className="copilotKitMarkdownElement" {...props}>
      {children}
    </div>
  ),
};

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
