package com.example.demo.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证控制器 - 提供登录 API
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
        @Valid @RequestBody LoginRequest credentials
    ) {
        String username = credentials.username();
        String password = credentials.password();

        String token = authService.login(username, password);

        if (token != null) {
            AuthService.AuthUser user = authService.validateToken(token);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "token", token,
                "username", username,
                "displayName", user.getDisplayName()
            ));
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
            "success", false,
            "message", "用户名或密码错误"
        ));
    }

    /**
     * 验证 Token（用于测试）
     */
    @GetMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(
        @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            AuthService.AuthUser user = authService.validateToken(token);

            if (user != null) {
                return ResponseEntity.ok(Map.of(
                    "valid", true,
                    "username", user.getUsername(),
                    "displayName", user.getDisplayName()
                ));
            }
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("valid", false));
    }

    public record LoginRequest(
        @NotBlank(message = "username 不能为空") String username,
        @NotBlank(message = "password 不能为空") String password
    ) {}
}
