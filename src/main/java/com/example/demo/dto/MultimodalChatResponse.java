package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 多模态聊天响应 DTO
 * 注意：使用 "response" 字段名保持与原有 ChatController 接口一致
 */
public record MultimodalChatResponse(
    @JsonProperty("response") String response
) {}
