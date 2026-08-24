package com.example.demo.service;

import com.example.demo.agent.SkillTools;
import com.example.demo.agent.SkillsAdvisor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.VectorStoreChatMemoryAdvisor;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.vectorstore.VectorStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.Ordered;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceTest {

    @Test
    void keepsMemoryAndRetrievalOutsideTheToolCallLoop() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        doReturn(builder).when(builder).defaultAdvisors(anyList());
        doReturn(builder).when(builder).defaultTools(any(Object[].class));
        when(builder.build()).thenReturn(mock(ChatClient.class));

        new AgentService(
            builder,
            mock(SkillTools.class),
            mock(SkillsAdvisor.class),
            mock(JdbcChatMemoryRepository.class),
            mock(VectorStore.class),
            mock(VectorStore.class),
            mock(ToolCallingManager.class),
            mock(ConversationIdResolver.class),
            new ObjectMapper(),
            true,
            true
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Advisor>> advisorsCaptor =
            ArgumentCaptor.forClass(List.class);
        verify(builder).defaultAdvisors(advisorsCaptor.capture());
        List<Advisor> advisors = advisorsCaptor.getValue();

        assertThat(advisors)
            .anyMatch(MessageChatMemoryAdvisor.class::isInstance)
            .anyMatch(VectorStoreChatMemoryAdvisor.class::isInstance)
            .anyMatch(QuestionAnswerAdvisor.class::isInstance)
            .anyMatch(ToolCallAdvisor.class::isInstance);

        int toolCallOrder = advisors.stream()
            .filter(ToolCallAdvisor.class::isInstance)
            .findFirst()
            .orElseThrow()
            .getOrder();

        assertThat(advisors)
            .filteredOn(advisor ->
                advisor instanceof MessageChatMemoryAdvisor
                    || advisor instanceof VectorStoreChatMemoryAdvisor
                    || advisor instanceof QuestionAnswerAdvisor)
            .allMatch(advisor -> advisor.getOrder() < toolCallOrder);
    }

    @Test
    void keepsAllEnabledAdvisorsInTheDocumentedAbsoluteOrder() {
        ChatClient.Builder builder = builder();
        SkillTools skillTools = mock(SkillTools.class);
        SkillsAdvisor skillsAdvisor = mock(SkillsAdvisor.class);
        when(skillsAdvisor.getOrder()).thenReturn(Ordered.HIGHEST_PRECEDENCE);

        new AgentService(
            builder,
            skillTools,
            skillsAdvisor,
            mock(JdbcChatMemoryRepository.class),
            mock(VectorStore.class),
            mock(VectorStore.class),
            mock(ToolCallingManager.class),
            mock(ConversationIdResolver.class),
            new ObjectMapper(),
            true,
            true
        );

        List<Advisor> advisors = capturedAdvisors(builder);

        assertThat(advisors)
            .extracting(Advisor::getOrder)
            .containsExactly(
                Ordered.HIGHEST_PRECEDENCE,
                Ordered.HIGHEST_PRECEDENCE + 100,
                Ordered.HIGHEST_PRECEDENCE + 150,
                Ordered.HIGHEST_PRECEDENCE + 200,
                Ordered.HIGHEST_PRECEDENCE + 300
            );
        assertThat(advisors)
            .extracting(advisor -> advisor.getClass().getSimpleName())
            .containsExactly(
                "SkillsAdvisor",
                "MessageChatMemoryAdvisor",
                "VectorStoreChatMemoryAdvisor",
                "QuestionAnswerAdvisor",
                "ToolCallAdvisor"
            );
    }

    @Test
    void omitsOptionalRetrievalAdvisorsWhenDisabled() {
        ChatClient.Builder builder = builder();
        SkillsAdvisor skillsAdvisor = mock(SkillsAdvisor.class);
        when(skillsAdvisor.getOrder()).thenReturn(Ordered.HIGHEST_PRECEDENCE);

        new AgentService(
            builder,
            mock(SkillTools.class),
            skillsAdvisor,
            mock(JdbcChatMemoryRepository.class),
            mock(VectorStore.class),
            mock(VectorStore.class),
            mock(ToolCallingManager.class),
            mock(ConversationIdResolver.class),
            new ObjectMapper(),
            false,
            false
        );

        List<Advisor> advisors = capturedAdvisors(builder);

        assertThat(advisors)
            .extracting(advisor -> advisor.getClass().getSimpleName())
            .containsExactly(
                "SkillsAdvisor",
                "MessageChatMemoryAdvisor",
                "ToolCallAdvisor"
            );
        assertThat(advisors.get(2).getOrder())
            .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 300);
    }

    @Test
    void registersTheProvidedSkillToolsAsChatClientDefaultTools() {
        ChatClient.Builder builder = builder();
        SkillTools skillTools = mock(SkillTools.class);
        SkillsAdvisor skillsAdvisor = mock(SkillsAdvisor.class);

        new AgentService(
            builder,
            skillTools,
            skillsAdvisor,
            mock(JdbcChatMemoryRepository.class),
            mock(VectorStore.class),
            mock(VectorStore.class),
            mock(ToolCallingManager.class),
            mock(ConversationIdResolver.class),
            new ObjectMapper(),
            false,
            false
        );

        ArgumentCaptor<Object[]> toolsCaptor =
            ArgumentCaptor.forClass(Object[].class);
        verify(builder).defaultTools(toolsCaptor.capture());

        assertThat(toolsCaptor.getValue()).containsExactly(skillTools);
    }

    private ChatClient.Builder builder() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        doReturn(builder).when(builder).defaultAdvisors(anyList());
        doReturn(builder).when(builder).defaultTools(any(Object[].class));
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return builder;
    }

    @SuppressWarnings("unchecked")
    private List<Advisor> capturedAdvisors(ChatClient.Builder builder) {
        ArgumentCaptor<List<Advisor>> advisorsCaptor =
            ArgumentCaptor.forClass(List.class);
        verify(builder).defaultAdvisors(advisorsCaptor.capture());
        return advisorsCaptor.getValue();
    }
}
