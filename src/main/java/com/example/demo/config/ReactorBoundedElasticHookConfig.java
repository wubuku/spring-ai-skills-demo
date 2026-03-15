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
 */
@Slf4j
@Configuration
public class ReactorBoundedElasticHookConfig {

    @PostConstruct
    public void init() {
        log.info("[ReactorBoundedElasticHookConfig] Registering boundedElastic schedule hook for user context");

        Function<Runnable, Runnable> decorator = runnable -> {
            // 在提交任务的线程里 capture 需要的上下文
            // 从 SecurityContextHolder 提取 JWT 和用户名
            String token = null;
            String username = null;

            try {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
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

            final String capturedToken = token;
            final String capturedUsername = username;

            return () -> {
                try {
                    // 这里已经是在 boundedElastic-* 线程
                    // 总是尝试设置上下文中捕获的 token（如果存在）
                    // 因为同一个 boundedElastic 线程可能被多个请求复用，必须用当前请求的 token 覆盖
                    if (capturedToken != null) {
                        UserContextHolder.setToken(capturedToken);
                        log.debug("[{}] [Hook] Set user context: username={}, hasToken={}",
                                Thread.currentThread().getName(),
                                capturedUsername,
                                capturedToken != null);
                    }
                    if (capturedUsername != null) {
                        UserContextHolder.setUsername(capturedUsername);
                    }

                    runnable.run();
                } finally {
                    // 清理 UserContextHolder，防止线程复用时 token 泄露
                    UserContextHolder.clear();
                    log.debug("[{}] [Hook] Task completed, user context cleared", Thread.currentThread().getName());
                }
            };
        };

        Schedulers.onScheduleHook("AguiBoundedElasticHook", decorator);
        log.info("[ReactorBoundedElasticHookConfig] BoundedElastic hook registered successfully");
    }
}
