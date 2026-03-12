import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import 'highlight.js/styles/github.css';

interface MarkdownRendererProps {
  content: string;
  className?: string;
}

/**
 * 统一的 Markdown 渲染组件
 * - 支持 GFM (GitHub Flavored Markdown)
 * - 支持代码高亮
 * - 自定义表格和其他元素的样式
 */
export function MarkdownRenderer({ content, className = '' }: MarkdownRendererProps) {
  return (
    <div className={`markdown-body ${className}`}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeHighlight]}
        components={{
          // 表格相关
          table: ({ children }) => (
            <div className="overflow-x-auto my-4">
              <table className="min-w-full border-collapse border border-gray-300 dark:border-gray-600">
                {children}
              </table>
            </div>
          ),
          thead: ({ children }) => (
            <thead className="bg-gray-100 dark:bg-gray-700">{children}</thead>
          ),
          tbody: ({ children }) => (
            <tbody className="divide-y divide-gray-200 dark:divide-gray-700">{children}</tbody>
          ),
          tr: ({ children }) => (
            <tr className="hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors">{children}</tr>
          ),
          th: ({ children }) => (
            <th className="border border-gray-300 dark:border-gray-600 px-4 py-2 text-left font-semibold text-gray-900 dark:text-gray-100">
              {children}
            </th>
          ),
          td: ({ children }) => (
            <td className="border border-gray-300 dark:border-gray-600 px-4 py-2 text-gray-700 dark:text-gray-300">
              {children}
            </td>
          ),

          // 文本相关
          p: ({ children }) => (
            <p className="my-2 leading-relaxed text-gray-800 dark:text-gray-200">{children}</p>
          ),

          // 列表相关
          ul: ({ children }) => (
            <ul className="list-disc list-inside my-2 space-y-1 text-gray-800 dark:text-gray-200">
              {children}
            </ul>
          ),
          ol: ({ children }) => (
            <ol className="list-decimal list-inside my-2 space-y-1 text-gray-800 dark:text-gray-200">
              {children}
            </ol>
          ),
          li: ({ children }) => (
            <li className="text-gray-700 dark:text-gray-300">{children}</li>
          ),

          // 代码相关
          code: ({ className, children }) => {
            const isInline = !className;
            return isInline ? (
              <code className="bg-gray-100 dark:bg-gray-800 px-1.5 py-0.5 rounded text-sm font-mono text-pink-600 dark:text-pink-400">
                {children}
              </code>
            ) : (
              <code className={className}>{children}</code>
            );
          },
          pre: ({ children }) => (
            <pre className="bg-gray-100 dark:bg-gray-800 p-4 rounded-lg overflow-x-auto my-3 border border-gray-200 dark:border-gray-700">
              {children}
            </pre>
          ),

          // 标题相关
          h1: ({ children }) => (
            <h1 className="text-2xl font-bold my-4 text-gray-900 dark:text-gray-100">{children}</h1>
          ),
          h2: ({ children }) => (
            <h2 className="text-xl font-bold my-3 text-gray-900 dark:text-gray-100">{children}</h2>
          ),
          h3: ({ children }) => (
            <h3 className="text-lg font-bold my-2 text-gray-900 dark:text-gray-100">{children}</h3>
          ),

          // 引用
          blockquote: ({ children }) => (
            <blockquote className="border-l-4 border-gray-300 dark:border-gray-600 pl-4 my-4 italic text-gray-700 dark:text-gray-300">
              {children}
            </blockquote>
          ),

          // 链接
          a: ({ href, children }) => (
            <a
              href={href}
              target="_blank"
              rel="noopener noreferrer"
              className="text-blue-600 dark:text-blue-400 hover:underline"
            >
              {children}
            </a>
          ),

          // 分割线
          hr: () => <hr className="my-6 border-gray-200 dark:border-gray-700" />,
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}
