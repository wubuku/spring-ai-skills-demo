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
}
