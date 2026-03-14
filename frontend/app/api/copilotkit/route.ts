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
 * 支持认证信息透传：
 * 1. 从前端 CopilotKit headers 获取 Authorization 头
 * 2. 转发到 Java 后端
 */

// 使用 nodejs 运行时，避免 Edge Runtime 的序列化问题
export const runtime = 'nodejs';

export const runtime_config = new CopilotRuntime({
  agents: {
    default: new HttpAgent({
      url: `${JAVA_BACKEND_URL}/api/agui`,
    }),
  },
});

const { handleRequest } = copilotRuntimeNextJSAppRouterEndpoint({
  runtime: runtime_config,
  endpoint: "/api/copilotkit",
});

export async function POST(req: NextRequest) {
  // CopilotKit 会自动将 headers 传入 handleRequest
  // HttpAgent 会自动转发原始请求的所有 headers
  return handleRequest(req);
}
