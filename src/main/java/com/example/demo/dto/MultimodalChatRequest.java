package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 多模态聊天请求 DTO（纯文本 JSON 接口用）
 */
public record MultimodalChatRequest(
    @NotBlank(message = "query 不能为空")
    @Size(max = 8192, message = "query 长度不能超过 8192")
    String query,
    String instruction,     // 可选：附加指令
    @Size(max = 128, message = "conversationId 长度不能超过 128")
    @Pattern(
        regexp = "[A-Za-z0-9._:-]*",
        message = "conversationId 包含非法字符"
    )
    String conversationId   // 会话 ID
) {}
