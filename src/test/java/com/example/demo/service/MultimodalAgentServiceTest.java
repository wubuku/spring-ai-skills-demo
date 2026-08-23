package com.example.demo.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultimodalAgentServiceTest {

    @Test
    void forwardsAnAlreadyResolvedConversationIdWithoutResolvingItAgain() {
        AgentService agentService = mock(AgentService.class);
        PromptLoader promptLoader = mock(PromptLoader.class);
        String resolvedConversationId = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";
        when(promptLoader.getLabel("label.user.input", "【用户输入】"))
            .thenReturn("【用户输入】");
        when(agentService.chatResolved(anyString(), eq(resolvedConversationId)))
            .thenReturn("ok");

        MultimodalAgentService service = new MultimodalAgentService(
            agentService,
            mock(ChatClient.class),
            mock(TranscriptionModel.class),
            promptLoader,
            mock(ConversationHistoryService.class),
            mock(ChatClient.class)
        );

        assertThat(service.chat("你好", resolvedConversationId, null, null, null))
            .isEqualTo("ok");
        verify(agentService).chatResolved("【用户输入】你好", resolvedConversationId);
    }
}
