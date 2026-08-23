package com.example.demo.service;

import com.example.demo.agent.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.VectorStoreChatMemoryAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentService {

    private static final int JDBC_MEMORY_ADVISOR_ORDER =
        Ordered.HIGHEST_PRECEDENCE + 100;
    private static final int VECTOR_MEMORY_ADVISOR_ORDER =
        Ordered.HIGHEST_PRECEDENCE + 150;
    private static final int RAG_ADVISOR_ORDER =
        Ordered.HIGHEST_PRECEDENCE + 200;
    private static final int TOOL_CALL_ADVISOR_ORDER =
        Ordered.HIGHEST_PRECEDENCE + 300;

    private final ChatClient chatClient;
    private final ConversationIdResolver conversationIdResolver;

    public AgentService(ChatClient.Builder builder,
                        SkillTools skillTools,
                        SkillsAdvisor skillsAdvisor,
                        JdbcChatMemoryRepository jdbcChatMemoryRepository,
                        @Qualifier("knowledgeVectorStore") VectorStore knowledgeVectorStore,
                        @Qualifier("chatMemoryVectorStore") VectorStore chatMemoryVectorStore,
                        ToolCallingManager toolCallingManager,
                        ConversationIdResolver conversationIdResolver,
                        @Value("${app.ai.rag.enabled:false}") boolean ragEnabled,
                        @Value("${app.ai.vector-memory.enabled:false}")
                        boolean vectorMemoryEnabled) {
        this.conversationIdResolver = conversationIdResolver;

        // 使用 JDBC 存储，保留最近 20 条消息的窗口
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(jdbcChatMemoryRepository)
                .maxMessages(20)
                .build();

        List<Advisor> advisors = new ArrayList<>();
        advisors.add(skillsAdvisor);
        advisors.add(MessageChatMemoryAdvisor.builder(chatMemory)
            .order(JDBC_MEMORY_ADVISOR_ORDER)
            .build());
        if (vectorMemoryEnabled) {
            advisors.add(VectorStoreChatMemoryAdvisor.builder(chatMemoryVectorStore)
                .order(VECTOR_MEMORY_ADVISOR_ORDER)
                .build());
        }
        if (ragEnabled) {
            advisors.add(QuestionAnswerAdvisor.builder(knowledgeVectorStore)
                .order(RAG_ADVISOR_ORDER)
                .build());
        }
        advisors.add(ToolCallAdvisor.builder()
            .toolCallingManager(toolCallingManager)
            .advisorOrder(TOOL_CALL_ADVISOR_ORDER)
            .suppressToolCallStreaming()
            .build());

        this.chatClient = builder
            .defaultAdvisors(advisors)
            .defaultTools(skillTools)
            .build();
    }

    public String chat(String userMessage) {
        return chat(userMessage, null);
    }

    public String chat(String userMessage, String conversationId) {
        Authentication authentication = currentAuthentication();
        String resolvedConversationId =
            conversationIdResolver.resolve(conversationId, authentication);
        return chatResolved(userMessage, resolvedConversationId, authentication);
    }

    String chatResolved(String userMessage, String resolvedConversationId) {
        return chatResolved(userMessage, resolvedConversationId, currentAuthentication());
    }

    private String chatResolved(String userMessage,
                                String resolvedConversationId,
                                Authentication authentication) {
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a
                    .param(ChatMemory.CONVERSATION_ID, resolvedConversationId)
                    .param(SkillsAdvisor.EXECUTION_MODE, SkillsAdvisor.MODE_BACKEND))
                .toolContext(toolContext(authentication))
                .call()
                .content();
    }

    public Flux<String> streamChat(String userMessage) {
        return streamChat(userMessage, null);
    }

    public Flux<String> streamChat(String userMessage, String conversationId) {
        Authentication authentication = currentAuthentication();
        String resolvedConversationId =
            conversationIdResolver.resolve(conversationId, authentication);
        return streamChatResolved(userMessage, resolvedConversationId, authentication);
    }

    Flux<String> streamChatResolved(String userMessage, String resolvedConversationId) {
        return streamChatResolved(
            userMessage,
            resolvedConversationId,
            currentAuthentication()
        );
    }

    private Flux<String> streamChatResolved(String userMessage,
                                            String resolvedConversationId,
                                            Authentication authentication) {
        Map<String, Object> toolContext = toolContext(authentication);
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a
                    .param(ChatMemory.CONVERSATION_ID, resolvedConversationId)
                    .param(SkillsAdvisor.EXECUTION_MODE, SkillsAdvisor.MODE_BACKEND))
                .toolContext(toolContext)
                .stream()
                .content();
    }

    private Authentication currentAuthentication() {
        Authentication authentication =
            SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
            ? authentication
            : null;
    }

    private Map<String, Object> toolContext(Authentication authentication) {
        Map<String, Object> context = new HashMap<>();
        context.put(SkillTools.SKILL_SESSION_CONTEXT_KEY, new SkillLoadSession());
        if (authentication != null) {
            Object credentials = authentication.getCredentials();
            if (credentials instanceof String token && !token.isBlank()) {
                context.put(SkillTools.AUTH_TOKEN_CONTEXT_KEY, token);
            }
            context.put(SkillTools.AUTH_USERNAME_CONTEXT_KEY, authentication.getName());
        }
        return context;
    }
}
