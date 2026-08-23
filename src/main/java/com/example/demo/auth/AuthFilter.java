package com.example.demo.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;

/**
 * 认证过滤器 - 从请求头提取 Token 并设置 Spring Security Context
 *
 * 使用 SecurityContextHolder 存储认证信息。普通 Agent 链路会在请求线程中把已验证
 * credentials 复制到 Spring AI ToolContext，不依赖全局 ThreadLocal 传播。
 */
@Slf4j
@Component
public class AuthFilter implements Filter {

    private final AuthService authService;

    public AuthFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String authHeader = httpRequest.getHeader("Authorization");
        log.debug("[AuthFilter] uri={}, bearerPresent={}",
            httpRequest.getRequestURI(), authHeader != null);

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            AuthService.AuthUser user = authService.validateToken(token);
            if (user != null) {
                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                        user.getUsername(),
                        token,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
                    );
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("[AuthFilter] authenticated user={}", user.getUsername());
            } else {
                log.debug("[AuthFilter] rejected invalid bearer token");
            }
        }

        chain.doFilter(request, response);
    }
}
