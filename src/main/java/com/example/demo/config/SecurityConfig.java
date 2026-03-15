package com.example.demo.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置
 *
 * 启用方法级安全保护 (@PreAuthorize 注解)
 * 使用简化的认证机制（硬编码用户凭证 Demo）
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 禁用 CSRF（前后端分离场景）
            .csrf(csrf -> csrf.disable())
            // 禁用 Session（使用 Token 认证）
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 配置访问规则
            .authorizeHttpRequests(auth -> auth
                // 公开端点
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/products").permitAll()
                .requestMatchers("/api/products/*").permitAll()
                .requestMatchers("/api/products/**").permitAll()
                .requestMatchers("/api/chat").permitAll()
                .requestMatchers("/api/explain-result").permitAll()
                .requestMatchers("/api/agui/**").permitAll()
                // PetStore API 公开端点
                .requestMatchers("/api/v3/store/inventory").permitAll()
                .requestMatchers("/api/v3/pet/**").permitAll()
                .requestMatchers("/api/v3/user/**").permitAll()
                .requestMatchers("/api/v3/store/order").permitAll()
                .requestMatchers("/h2-console/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/").permitAll()
                .requestMatchers("/index.html").permitAll()
                // 受保护端点需要认证
                .anyRequest().authenticated()
            )
            // 允许 H2 控制台
            .headers(headers -> headers.frameOptions(frame -> frame.disable()));

        return http.build();
    }
}
