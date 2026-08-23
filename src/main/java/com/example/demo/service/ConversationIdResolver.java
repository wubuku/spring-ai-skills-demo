package com.example.demo.service;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class ConversationIdResolver {

    private static final int MAX_LENGTH = 128;
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._:-]+");

    public String resolve(String requestedId, Authentication authentication) {
        String clientId = requestedId == null ? "" : requestedId.trim();
        if (clientId.isEmpty() || "default".equals(clientId)) {
            clientId = UUID.randomUUID().toString();
        } else if (clientId.length() > MAX_LENGTH || !VALID_ID.matcher(clientId).matches()) {
            throw new IllegalArgumentException(
                "conversationId 仅允许字母、数字、点、下划线、连字符和冒号，长度不超过 128");
        }

        String namespace;
        if (authentication != null && authentication.isAuthenticated()
            && authentication.getPrincipal() != null
            && !"anonymousUser".equals(authentication.getPrincipal().toString())) {
            namespace = "user:" + authentication.getName();
        } else {
            namespace = "anonymous";
        }

        return UUID.nameUUIDFromBytes(
            (namespace + ":" + clientId).getBytes(StandardCharsets.UTF_8)
        ).toString();
    }
}
