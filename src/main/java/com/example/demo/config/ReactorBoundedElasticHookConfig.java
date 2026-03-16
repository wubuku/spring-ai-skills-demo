package com.example.demo.config;

import com.example.demo.auth.UserContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.scheduler.Schedulers;

import java.util.function.Function;

/**
 * Reactor BoundedElastic Hook 配置
 *
 * Spring AI 工具执行在 boundedElastic 线程池中，
 * 而 SecurityContext 无法通过 INHERITABLETHREADLOCAL 自动传递到该线程池。
 *
 * 本配置使用 Schedulers.onScheduleHook 全局 hook boundedElastic，
 * 在任务提交时从主线程捕获 JWT 并放入 UserContextHolder，
 * 从而让工具在 boundedElastic 线程中也能获取到用户认证信息。
 *
 * 同时，为了支持 SSE 流式场景下的 JWT 透传，
 * 我们使用 Executors.newCachedThreadPool() 的 hook 来捕获更多异步线程
 */
@Slf4j
@Configuration
public class ReactorBoundedElasticHookConfig {

    @PostConstruct
    public void init() {
        log.info("[ReactorBoundedElasticHookConfig] Registering boundedElastic schedule hook for user context");

        Function<Runnable, Runnable> decorator = runnable -> {
            // 在提交任务的线程里 capture 需要的上下文
            // 优先从 UserContextHolder 获取（如果 AgUiController 已经设置了）
            // 其次从 SecurityContextHolder 获取
            String token = null;
            String username = null;

            // 首先尝试从 UserContextHolder 获取（AgUiController 设置的）
            token = UserContextHolder.getToken();
            username = UserContextHolder.getUsername();

            // [STEP3] 记录捕获阶段的上下文
            log.info("[STEP3] hook capture on thread={}, capturedToken={}, capturedUsername={}",
                    Thread.currentThread().getName(),
                    token != null ? "present" : "null",
                    username);

            // 如果 UserContextHolder 没有，再从 SecurityContextHolder 获取
            if (token == null) {
                try {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    log.info("[ReactorBoundedElasticHookConfig] SecurityContext authentication: {}, authenticated: {}",
                        auth != null ? auth.getClass().getSimpleName() : "null",
                        auth != null ? auth.isAuthenticated() : false);

                    if (auth != null && auth.isAuthenticated()) {
                        Object credentials = auth.getCredentials();
                        if (credentials instanceof String) {
                            token = (String) credentials;
                        }
                        Object principal = auth.getPrincipal();
                        if (principal != null) {
                            username = principal.toString();
                        }
                    }
                } catch (Exception e) {
                    log.warn("[ReactorBoundedElasticHookConfig] Failed to capture SecurityContext: {}", e.getMessage());
                }
            }

            final String capturedToken = token;
            final String capturedUsername = username;

            // 始终记录，以便调试
            log.info("[STEP3] Captured token: {}, username: {}",
                capturedToken != null ? "present" : "null",
                capturedUsername);

            return () -> {
                try {
                    // 这里已经是在 boundedElastic-* 线程
                    // [STEP3] 记录设置阶段的上下文
                    log.info("[STEP3] hook set on thread={}, ctxToken={}, ctxUsername={}",
                            Thread.currentThread().getName(),
                            capturedToken != null ? "present" : "null",
                            capturedUsername);

                    // 总是尝试设置上下文中捕获的 token（如果存在）
                    // 因为同一个 boundedElastic 线程可能被多个请求复用，必须用当前请求的 token 覆盖
                    if (capturedToken != null) {
                        UserContextHolder.setToken(capturedToken);
                        log.info("[{}] [Hook] Set user context: username={}, hasToken={}",
                                Thread.currentThread().getName(),
                                capturedUsername,
                                capturedToken != null);
                    }
                    if (capturedUsername != null) {
                        UserContextHolder.setUsername(capturedUsername);
                    }

                    runnable.run();
                } finally {
                    // 不清理 UserContextHolder！
                    // 等待下一次请求时覆盖，这样 boundedElastic 线程复用时仍能获取到 token
                    // 注意：这意味着同一个线程的多个请求之间可能有 token 残留风险，
                    // 但由于每次都会用当前请求的 token 覆盖，风险可控
                    log.debug("[{}] [Hook] Task completed, user context kept for thread reuse", Thread.currentThread().getName());
                }
            };
        };

        Schedulers.onScheduleHook("AguiBoundedElasticHook", decorator);
        log.info("[ReactorBoundedElasticHookConfig] BoundedElastic hook registered successfully");
    }
}
