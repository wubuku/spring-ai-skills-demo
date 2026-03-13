"use client";

import React from "react";
import { CopilotPopup } from "@copilotkit/react-ui";
import { ConfirmDialogContainer, HttpRequestMeta } from "@/components/ConfirmDialogContainer";

export default function Home() {
  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-100 dark:from-gray-900 dark:to-gray-800">
      <main className="container mx-auto px-4 py-8">
        {/* Header */}
        <div className="text-center mb-12">
          <h1 className="text-4xl font-bold text-gray-800 dark:text-white mb-4">
            企业智能助手
          </h1>
          <p className="text-lg text-gray-600 dark:text-gray-300">
            基于 CopilotKit 的现代化智能体界面
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
          AssistantMessage={undefined}
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
            // pre 组件：拦截 http-request 代码块，渲染确认对话框
            pre: ({ children, ...props }: any) => {
              // 检测 http-request 代码块
              if (React.isValidElement(children)) {
                const codeProps = (children as React.ReactElement<any>).props;
                const className = codeProps?.className || '';

                // 处理两种格式：
                // 1. 正确格式：language-http-request（然后换行有 JSON）
                // 2. AI 错误格式：language-http-request{...}（JSON 直接拼接无换行）
                if (className === 'language-http-request' ||
                    className.startsWith('language-http-request')) {
                  try {
                    let content = String(codeProps.children).trim();
                    let requestMeta: HttpRequestMeta;

                    if (className.startsWith('language-http-request{')) {
                      // 错误格式：JSON 在 className 中
                      // className 类似 "language-http-request{"method":"POST",...}"
                      const jsonStr = className.replace('language-http-request', '');
                      requestMeta = JSON.parse(jsonStr);
                    } else {
                      // 正确格式：JSON 在 children 中
                      requestMeta = JSON.parse(content);
                    }

                    // 使用稳定的 key，确保流式渲染时状态不丢失
                    const stableKey = `${requestMeta.method}-${requestMeta.url}`;
                    return <ConfirmDialogContainer key={stableKey} requestMeta={requestMeta} />;
                  } catch {
                    // JSON 解析失败，降级到普通代码块渲染
                  }
                }
              }
              // 正常的 pre 渲染（保留默认 CodeBlock 语法高亮）
              return (
                <pre className="bg-gray-100 dark:bg-gray-800 p-4 rounded-lg overflow-x-auto my-2" {...props}>
                  {children}
                </pre>
              );
            },
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
