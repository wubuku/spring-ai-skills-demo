package com.example.demo.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationIdResolverTest {

    private final ConversationIdResolver resolver = new ConversationIdResolver();

    @Test
    void namespacesTheClientConversationByAuthenticatedUser() {
        var user1 = UsernamePasswordAuthenticationToken.authenticated(
            "user1", "token", java.util.List.of());
        var user2 = UsernamePasswordAuthenticationToken.authenticated(
            "user2", "token", java.util.List.of());

        String firstUser = resolver.resolve("browser-session", user1);
        String secondUser = resolver.resolve("browser-session", user2);

        assertThat(firstUser)
            .hasSize(36)
            .isEqualTo(resolver.resolve("browser-session", user1))
            .isNotEqualTo(secondUser);
        assertThatCode(() -> UUID.fromString(firstUser)).doesNotThrowAnyException();
    }

    @Test
    void createsAnIsolatedAnonymousIdWhenTheClientDoesNotSupplyOne() {
        String first = resolver.resolve(null, null);
        String second = resolver.resolve(" ", null);

        assertThat(first).hasSize(36);
        assertThat(second).hasSize(36);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void rejectsUnsafeOrUnboundedClientIds() {
        assertThatThrownBy(() -> resolver.resolve("../shared", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve("x".repeat(129), null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
