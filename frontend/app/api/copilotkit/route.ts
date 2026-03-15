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
 * 1. 从前端请求头获取 Authorization 头
 * 2. 转发到 Java 后端
 *
 * Demo 测试用户：
 * - user1 / password1 (张三)
 * - user2 / password2 (李四)
 *
 * Token 格式：base64(username:password)
 * 例如：user1:password1  ->  dXNlcjE6cGFzc3dvcmQx
 */

const runtime = new CopilotRuntime({
  agents: {
    // 使用 "default" 名称，CopilotPopup 会自动使用这个 agent
    default: new HttpAgent({
      url: `${JAVA_BACKEND_URL}/api/agui`,
      // headers 会在请求时动态获取
    }),
  },
});

const { handleRequest } = copilotRuntimeNextJSAppRouterEndpoint({
  runtime,
  endpoint: "/api/copilotkit",
});

export async function POST(req: NextRequest) {
  // 从前端请求中获取 Authorization 头
  const authHeader = req.headers.get("authorization");

  // 创建一个新的 Headers 对象来传递额外的认证信息
  const headers = new Headers();
  if (authHeader) {
    headers.set("Authorization", authHeader);
  }

  // 克隆请求并添加 headers
  const clonedReq = new Request(req.url, {
    method: req.method,
    headers: {
      ...Object.fromEntries(req.headers),
      ...Object.fromEntries(headers),
    },
    body: req.body,
    // @ts-ignore - duplex 类型兼容性
    duplex: "half",
  });

  return handleRequest(clonedReq as NextRequest);
}
