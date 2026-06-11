"use client";

import React from "react";
import {
  AssistantMessage as DefaultAssistantMessage,
  type AssistantMessageProps,
} from "@copilotkit/react-ui";

/**
 * CopilotAssistantMessage - 简化版透传组件
 *
 * 重构说明（2026-06-02）：
 * - 旧版本实现了自定义的 `extractHttpRequestMeta` 解析 http-request 代码块
 * - 现已迁移到 CopilotKit 原生工具调用机制（`useHttpRequestTool` Hook）
 * - 工具调用由 CopilotKit 自动处理，不再需要手动解析文本
 * - `[CONFIRM_REQUIRED]` 前缀清理也一并移除（与原生工具调用不兼容）
 *
 * 此文件目前未被 `app/page.tsx` 引用（page.tsx 已切换到 DefaultAssistantMessage），
 * 保留作为兼容/参考使用，可以直接删除。
 */
export function CopilotAssistantMessage(props: AssistantMessageProps) {
  return <DefaultAssistantMessage {...props} />;
}
