import {
  CopilotRuntime,
  copilotRuntimeNextJSAppRouterEndpoint,
} from "@copilotkit/runtime";
import { HttpAgent } from "@ag-ui/client";
import { NextRequest } from "next/server";

const JAVA_BACKEND_URL = process.env.JAVA_BACKEND_URL || "http://localhost:8080";

/**
 * CopilotKit Runtime BFF 层
 *
 * 根据官方文档正确配置：
 * 1. 使用 HttpAgent 包装 Java 后端的 AG-UI 端点
 * 2. 使用对象格式注册 agents（不是数组）
 * 3. 使用 "default" 作为 agent 名称，让 CopilotPopup 自动使用
 */

const runtime = new CopilotRuntime({
  agents: {
    // 使用 "default" 名称，CopilotPopup 会自动使用这个 agent
    default: new HttpAgent({
      url: `${JAVA_BACKEND_URL}/api/agui`,
    }),
  },
});

const { handleRequest } = copilotRuntimeNextJSAppRouterEndpoint({
  runtime,
  endpoint: "/api/copilotkit",
});

export async function POST(req: NextRequest) {
  return handleRequest(req);
}
