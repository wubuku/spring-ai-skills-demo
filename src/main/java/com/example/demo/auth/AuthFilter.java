package com.example.demo.auth;

import com.example.demo.auth.UserContextHolder;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
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
 * 同时设置 UserContextHolder 供 Reactor hook 捕获用户上下文
 */
@Slf4j
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
            log.info("[AuthFilter] URI: {}, AuthHeader 存在: {}", httpRequest.getRequestURI(), authHeader != null);

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                log.info("[AuthFilter] Token 前20字符: {}", token.substring(0, Math.min(20, token.length())));

                // 验证 Token
                AuthService.AuthUser user = authService.validateToken(token);
                if (user != null) {
                    log.info("[AuthFilter] Token 验证成功, 用户: {}", user.getUsername());
                    // 设置到 Spring Security ContextHolder
                    // 这样 SecurityContextHolder + INHERITABLETHREADLOCAL 机制就能工作
                    UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                            user.getUsername(),  // principal
                            token,              // credentials - 存储原始 JWT
                            Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
                        );
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.info("[AuthFilter] SecurityContext 已设置");

                    // 设置到 UserContextHolder，供 Reactor hook 捕获并传递到 boundedElastic 线程
                    UserContextHolder.setToken(token);
                    UserContextHolder.setUsername(user.getUsername());
                    log.info("[AuthFilter] UserContextHolder 已设置");
                } else {
                    log.warn("[AuthFilter] Token 验证失败");
                }
            }

            chain.doFilter(request, response);

        } finally {
            // 请求结束时清理 UserContextHolder
            UserContextHolder.clear();
            log.debug("[AuthFilter] UserContextHolder 已清理");
        }
    }
}
