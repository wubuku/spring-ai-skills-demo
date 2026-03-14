package com.example.demo.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;

/**
 * 认证过滤器 - 从请求头提取 Token 并设置 Spring Security Context
 *
 * 使用 SecurityContextHolder 存储认证信息，这是 Spring Security 标准
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
                    // 设置到 Spring Security ContextHolder
                    // 这样 SecurityContextHolder + INHERITABLETHREADLOCAL 机制就能工作
                    UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                            user.getUsername(),  // principal
                            token,              // credentials - 存储原始 JWT
                            Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
                        );
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }

            chain.doFilter(request, response);

        } finally {
            // Spring Security 会在请求结束时自动清理 SecurityContext
        }
    }
}
