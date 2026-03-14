package com.example.demo.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简化的认证服务 - 用于 Demo 演示认证透传机制
 *
 * 注意：这是 Demo 代码，生产环境请使用 Spring Security + JWT
 */
@Service
public class AuthService {

    // 硬编码用户数据（Demo 用途）
    private static final Map<String, UserCredential> USERS = new ConcurrentHashMap<>();

    static {
        // 添加测试用户
        USERS.put("user1", new UserCredential("user1", "password1", "张三"));
        USERS.put("user2", new UserCredential("user2", "password2", "李四"));
        USERS.put("admin", new UserCredential("admin", "admin123", "管理员"));
    }

    /**
     * 用户凭证
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class UserCredential {
        private String username;
        private String password;
        private String displayName;
    }

    /**
     * 登录 - 生成 Token
     */
    public String login(String username, String password) {
        UserCredential user = USERS.get(username);
        if (user != null && user.getPassword().equals(password)) {
            // 简单 Token 格式：base64(username:displayName)
            String tokenData = username + ":" + user.getDisplayName();
            return Base64.getEncoder().encodeToString(tokenData.getBytes());
        }
        return null;
    }

    /**
     * 验证 Token 并获取用户信息
     */
    public AuthUser validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            // 移除 "Bearer " 前缀（如果存在）
            if (token.startsWith("Bearer ")) {
                token = token.substring(7);
            }

            String decoded = new String(Base64.getDecoder().decode(token));
            String[] parts = decoded.split(":", 2);
            if (parts.length == 2) {
                String username = parts[0];
                String displayName = parts[1];

                // 验证用户存在
                if (USERS.containsKey(username)) {
                    return new AuthUser(username, displayName);
                }
            }
        } catch (Exception e) {
            // Token 无效
        }
        return null;
    }

    /**
     * 认证后的用户信息
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AuthUser {
        private String username;
        private String displayName;
    }
}
