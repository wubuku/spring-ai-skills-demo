package com.example.demo.dto;

/**
 * 多模态聊天请求 DTO（纯文本 JSON 接口用）
 */
public record MultimodalChatRequest(
    String query,           // 用户文本输入
    String instruction,     // 可选：附加指令
    String conversationId   // 会话 ID
) {}
