/**
 * Next.js Instrumentation
 *
 * 在 Next.js 服务启动时配置 undici（Node.js 内置 fetch 实现）的默认 Agent。
 * 默认 bodyTimeout=300000ms（5分钟），对于 SSE 长连接场景太短，
 * LLM 流式响应 + 工具调用 + SSE 事件推送很容易超过 5 分钟。
 *
 * 设置 bodyTimeout=0 / headersTimeout=0 表示无超时，让 SSE 长连接可以持续工作。
 *
 * 参考：
 * - https://nextjs.org/docs/app/api-reference/file-conventions/instrumentation
 * - https://github.com/nodejs/undici/blob/main/docs/api/Agent.md
 */
export async function register() {
  if (process.env.NEXT_RUNTIME === 'nodejs') {
    // undici 是 Node.js 18+ 内置 fetch 实现的依赖
    // 在 Next.js dev 环境下，webpack 可能不识别 'undici' 字符串字面量
    // 使用 eval 绕过静态分析（运行时从 Node 全局加载 undici）
    const undiciModuleName = 'undici';
    // eslint-disable-next-line @typescript-eslint/no-implied-eval
    const undici = eval('require')(undiciModuleName) as any;
    const { setGlobalDispatcher, Agent } = undici;
    setGlobalDispatcher(
      new Agent({
        bodyTimeout: 0,         // 0 = 无超时
        headersTimeout: 0,      // 0 = 无超时
        keepAliveTimeout: 60_000,
      })
    );
    // 静默成功（避免日志噪音）
    // eslint-disable-next-line no-console
    console.log('[instrumentation] undici Agent configured: bodyTimeout=0, headersTimeout=0');
  }
}
