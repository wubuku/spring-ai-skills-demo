"use client";

import React from "react";
import {
  AssistantMessage as DefaultAssistantMessage,
  type AssistantMessageProps,
} from "@copilotkit/react-ui";
import {
  ConfirmDialogContainer,
  type HttpRequestMeta,
} from "@/components/ConfirmDialogContainer";

const requestMetaCache = new Map<string, HttpRequestMeta>();

function extractHttpRequestMeta(content: string): {
  cleanedContent: string;
  requestMeta?: HttpRequestMeta;
} {
  // 直接搜索 ```http-request 代码块开始标记
  const marker = "```http-request";
  const startIdx = content.indexOf(marker);

  if (startIdx === -1) {
    return {
      cleanedContent: content.replace(/\[CONFIRM_REQUIRED\]\s*/g, "").trim(),
    };
  }

  // 找到结束标记 ```
  const codeStart = startIdx + marker.length;
  const endIdx = content.indexOf("```", codeStart);

  let jsonContent = "";
  let cleanedContent = content;

  if (endIdx !== -1) {
    jsonContent = content.substring(codeStart, endIdx).trim();
    // 移除整个代码块
    cleanedContent = content.substring(0, startIdx) + content.substring(endIdx + 3);
  }

  let requestMeta: HttpRequestMeta | undefined;

  if (jsonContent) {
    try {
      const raw = JSON.parse(jsonContent);
      requestMeta = {
        ...raw,
        params: raw.params ?? raw.queryParams,
      };
    } catch {
      // JSON 解析失败，忽略
    }
  }

  cleanedContent = cleanedContent
    .replace(/\[CONFIRM_REQUIRED\]\s*/g, "")
    .replace(/\n{3,}/g, "\n\n")
    .trim();

  return { cleanedContent, requestMeta };
}

export function CopilotAssistantMessage(props: AssistantMessageProps) {
  const messageId = props.message?.id;
  const content = props.message?.content ?? "";

  // 调试：打印消息内容
  if (messageId && content.includes('确认')) {
    console.log('[CopilotAssistantMessage] Message content:', content.substring(0, 500));
  }

  const { cleanedContent, requestMeta } = extractHttpRequestMeta(content);

  // 调试
  if (messageId && content.includes('http-request')) {
    console.log('[CopilotAssistantMessage] Extracted requestMeta:', requestMeta);
  }

  // 直接使用当前解析出的 requestMeta，不需要缓存
  // 因为每次 content 变化都会重新解析
  const normalizedMessage = props.message
    ? {
        ...props.message,
        content: cleanedContent,
      }
    : props.message;

  return (
    <DefaultAssistantMessage
      {...props}
      message={normalizedMessage}
      subComponent={
        requestMeta ? (
          <ConfirmDialogContainer
            key={`${messageId}-${requestMeta.method}-${requestMeta.url}`}
            messageId={messageId}
            requestMeta={requestMeta}
          />
        ) : undefined
      }
    />
  );
}
