package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    private String role;     // "user" or "assistant"
    @JsonAlias("message")    // Accept both "content" and "message"
    @NotBlank(message = "content 不能为空")
    @Size(max = 8192, message = "content 长度不能超过 8192")
    private String content;
    @Size(max = 128, message = "conversationId 长度不能超过 128")
    @Pattern(
        regexp = "[A-Za-z0-9._:-]*",
        message = "conversationId 包含非法字符"
    )
    private String conversationId;  // 会话 ID，用于记忆系统区分不同会话
}
