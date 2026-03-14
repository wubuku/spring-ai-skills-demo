package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * CORS 跨域配置
 * 允许前端（Next.js）访问后端 API
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // 允许的源（前端地址）
        config.addAllowedOrigin("http://localhost:3000"); // Next.js 开发服务器
        config.addAllowedOrigin("http://localhost:3001");
        config.addAllowedOrigin("http://localhost:3002");
        config.addAllowedOrigin("http://localhost:4000");
        config.addAllowedOrigin("http://127.0.0.1:3000");
        config.addAllowedOrigin("http://127.0.0.1:3001");
        config.addAllowedOrigin("http://127.0.0.1:3002");
        config.addAllowedOrigin("http://127.0.0.1:4000");

        // 允许的 HTTP 方法
        config.addAllowedMethod("*");

        // 允许的请求头
        config.addAllowedHeader("*");

        // 允许携带凭证（cookies）
        config.setAllowCredentials(true);

        // 预检请求的有效期（秒）
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);

        return new CorsFilter(source);
    }
}
