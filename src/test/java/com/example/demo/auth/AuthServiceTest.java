package com.example.demo.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AuthServiceTest {

    private final AuthService authService = new AuthService();

    @Test
    void acceptsOnlyTheConfiguredUsernameAndPasswordPair() {
        String token = authService.login("user1", "password1");

        assertThat(token).isNotBlank();
        assertThat(authService.validateToken(token))
            .extracting(AuthService.AuthUser::getUsername, AuthService.AuthUser::getDisplayName)
            .containsExactly("user1", "张三");
        assertThat(authService.validateToken(encode("user1:wrong-password"))).isNull();
        assertThat(authService.validateToken(encode("user1:伪造显示名"))).isNull();
    }

    @Test
    void rejectsMalformedUnknownAndEmptyTokens() {
        assertThat(authService.validateToken(null)).isNull();
        assertThat(authService.validateToken("")).isNull();
        assertThat(authService.validateToken("not-base64")).isNull();
        assertThat(authService.validateToken(encode("unknown:anything"))).isNull();
    }

    private String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
