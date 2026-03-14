package com.example.demo.auth;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证控制器 - 提供登录 API
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        String token = authService.login(username, password);

        if (token != null) {
            AuthService.AuthUser user = new AuthService.AuthUser(username,
                username.equals("user1") ? "张三" :
                username.equals("user2") ? "李四" : "管理员");
            return Map.of(
                "success", true,
                "token", token,
                "username", username,
                "displayName", user.getDisplayName()
            );
        }

        return Map.of(
            "success", false,
            "message", "用户名或密码错误"
        );
    }

    /**
     * 验证 Token（用于测试）
     */
    @GetMapping("/verify")
    public Map<String, Object> verify(@RequestHeader("Authorization") String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            AuthService.AuthUser user = authService.validateToken(token);

            if (user != null) {
                return Map.of(
                    "valid", true,
                    "username", user.getUsername(),
                    "displayName", user.getDisplayName()
                );
            }
        }

        return Map.of("valid", false);
    }
}
