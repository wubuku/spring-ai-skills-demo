package com.example.demo.agent;

import com.example.demo.model.PendingHttpRequest;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 请求级写操作确认状态。
 *
 * 该对象只放入当前 ChatClient 调用的 ToolContext，不注册为 Spring singleton，
 * 因此不同用户、会话和并发请求之间不会共享待确认动作。
 */
public final class MutationConfirmationSession {

    public static final String CONTEXT_KEY = "skills.mutation-confirmation-session";

    private final AtomicReference<PendingHttpRequest> pending = new AtomicReference<>();

    public boolean register(PendingHttpRequest request) {
        if (request == null) {
            return false;
        }
        return pending.compareAndSet(null, request);
    }

    public Optional<PendingHttpRequest> pending() {
        return Optional.ofNullable(pending.get());
    }
}
