package com.example.demo.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 认证过滤器 - 从请求头提取 Token 并设置 AuthContext
 *
 * 注意：这是 Demo 代码，生产环境请使用 Spring Security 的过滤器
 */
@Component
@Order(1)
public class AuthFilter implements Filter {

    private final AuthService authService;

    public AuthFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        try {
            // 从请求头获取 Authorization
            String authHeader = httpRequest.getHeader("Authorization");

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);

                // 验证 Token
                AuthService.AuthUser user = authService.validateToken(token);
                if (user != null) {
                    // 设置到 ThreadLocal
                    AuthContext.setCurrentUser(user);
                }
            }

            chain.doFilter(request, response);

        } finally {
            // 清理 ThreadLocal
            AuthContext.clear();
        }
    }
}
