package com.example.demo.auth;

/**
 * 认证上下文 - 使用 ThreadLocal 存储当前请求的用户信息
 *
 * 注意：这是 Demo 代码，生产环境请使用 Spring Security 的 SecurityContextHolder
 */
public class AuthContext {

    private static final ThreadLocal<AuthService.AuthUser> CURRENT_USER = new ThreadLocal<>();

    /**
     * 设置当前用户
     */
    public static void setCurrentUser(AuthService.AuthUser user) {
        CURRENT_USER.set(user);
    }

    /**
     * 获取当前用户
     */
    public static AuthService.AuthUser getCurrentUser() {
        return CURRENT_USER.get();
    }

    /**
     * 清除当前用户
     */
    public static void clear() {
        CURRENT_USER.remove();
    }

    /**
     * 检查是否已认证
     */
    public static boolean isAuthenticated() {
        return CURRENT_USER.get() != null;
    }

    /**
     * 获取当前用户名
     */
    public static String getUsername() {
        AuthService.AuthUser user = CURRENT_USER.get();
        return user != null ? user.getUsername() : null;
    }

    /**
     * 获取当前用户显示名称
     */
    public static String getDisplayName() {
        AuthService.AuthUser user = CURRENT_USER.get();
        return user != null ? user.getDisplayName() : null;
    }
}
