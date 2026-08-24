package com.example.demo.service;

import com.example.demo.model.PendingHttpRequest;

/**
 * 普通 Agent 的同步结果。
 *
 * confirmation 非空时，response 是由后端生成的安全说明；不能采信模型在最终文本中
 * 对写操作状态的自由描述。
 */
public record AgentChatResult(
    String response,
    PendingHttpRequest confirmation
) {}
