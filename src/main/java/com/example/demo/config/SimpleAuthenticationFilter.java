package com.example.demo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Base64;
import java.util.Collections;

/**
 * 简化的认证过滤器 - 从请求头解析 Token 并设置 Spring Security 上下文
 *
 * Demo 说明：硬编码用户凭证验证机制
 * 生产环境请使用标准的 JWT 验证
 */
@Slf4j
@Component
public class SimpleAuthenticationFilter extends OncePerRequestFilter {

    // 硬编码用户（Demo 用途）
    private static final java.util.Map<String, String> USERS = new java.util.concurrent.ConcurrentHashMap<>();
    static {
        USERS.put("user1:password1", "张三");
        USERS.put("user2:password2", "李四");
        USERS.put("admin:admin123", "管理员");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        try {
            String authHeader = request.getHeader("Authorization");

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);

                // 验证 Token 并设置 SecurityContext
                String username = validateTokenAndGetUsername(token);
                if (username != null) {
                    // 创建 Authentication 对象并设置到 SecurityContextHolder
                    UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                            username,  // principal
                            null,      // credentials
                            Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")) // authorities
                        );

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.debug("认证成功: username={}", username);
                }
            }

            filterChain.doFilter(request, response);

        } finally {
            // 注意：这里不清空 SecurityContext，因为后续的 Controller/Service 可能需要
            // Spring Security 会在请求结束时自动清理
        }
    }

    /**
     * 验证 Token 并返回用户名
     * Demo 实现：Token = base64(username:password)
     */
    private String validateTokenAndGetUsername(String token) {
        try {
            // 解码 Token（Demo 格式：base64(username:password)）
            String decoded = new String(Base64.getDecoder().decode(token));
            String[] parts = decoded.split(":", 2);

            if (parts.length == 2) {
                String username = parts[0];
                String password = parts[0] + ":" + parts[1];

                // 验证用户凭证
                if (USERS.containsKey(password)) {
                    return username;
                }
            }
        } catch (Exception e) {
            log.debug("Token 验证失败: {}", e.getMessage());
        }
        return null;
    }
}
