package com.example.demo.auth;

/**
 * 用户上下文持有者
 * 用于在 Reactor boundedElastic 线程池中传递用户认证信息
 *
 * 由于 Spring AI 工具执行在 boundedElastic 线程池中，
 * 而 SecurityContext 无法通过 INHERITABLETHREADLOCAL 自动传递到该线程池，
 * 因此使用自定义的 UserContextHolder 来传递 JWT Token
 */
public class UserContextHolder {

    private static final ThreadLocal<String> JWT_TOKEN = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();

    public static void setToken(String token) {
        JWT_TOKEN.set(token);
    }

    public static String getToken() {
        return JWT_TOKEN.get();
    }

    public static void setUsername(String username) {
        USERNAME.set(username);
    }

    public static String getUsername() {
        return USERNAME.get();
    }

    public static void clear() {
        JWT_TOKEN.remove();
        USERNAME.remove();
    }

    public static boolean hasToken() {
        return JWT_TOKEN.get() != null;
    }
}
