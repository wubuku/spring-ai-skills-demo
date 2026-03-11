"use client";

import { CopilotPopup } from "@copilotkit/react-ui";

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
