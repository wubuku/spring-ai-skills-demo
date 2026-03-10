package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    private String role;     // "user" or "assistant"
    @JsonAlias("message")    // Accept both "content" and "message"
    private String content;
    private String conversationId;  // 会话 ID，用于记忆系统区分不同会话
}
