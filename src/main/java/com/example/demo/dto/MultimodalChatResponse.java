package com.example.demo.dto;

import com.example.demo.model.PendingHttpRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 多模态聊天响应 DTO
 * 注意：使用 "response" 字段名保持与原有 ChatController 接口一致
 */
public record MultimodalChatResponse(
    @JsonProperty("response") String response,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("confirmation") PendingHttpRequest confirmation
) {
    public MultimodalChatResponse(String response) {
        this(response, null);
    }
}
