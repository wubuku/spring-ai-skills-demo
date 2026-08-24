package com.example.demo.controller;

import com.example.demo.service.AgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private AgentService agentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ChatController controller = new ChatController(
            agentService,
            new ObjectMapper(),
            10_000
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    @Test
    void streamsJsonChunksInOrderAndFinishesWithDoneMarker() throws Exception {
        when(agentService.streamChat("hello", "sse-contract"))
            .thenReturn(Flux.just("first", "second"));

        MvcResult started = mockMvc.perform(post("/api/chat/stream")
                .contentType(APPLICATION_JSON)
                .accept(TEXT_EVENT_STREAM)
                .content("""
                    {"content":"hello","conversationId":"sse-contract"}
                    """))
            .andExpect(request().asyncStarted())
            .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(TEXT_EVENT_STREAM))
            .andReturn();

        String body = completed.getResponse().getContentAsString();
        assertThat(body)
            .contains("\"content\":\"first\"")
            .contains("\"content\":\"second\"")
            .contains("[DONE]");
        assertThat(body.indexOf("\"content\":\"first\""))
            .isLessThan(body.indexOf("\"content\":\"second\""));
        assertThat(body.lastIndexOf("[DONE]"))
            .isGreaterThan(body.indexOf("\"content\":\"second\""));
        verify(agentService).streamChat("hello", "sse-contract");
    }
}
