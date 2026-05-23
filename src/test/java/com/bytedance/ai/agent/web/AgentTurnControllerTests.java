package com.bytedance.ai.agent.web;

import com.bytedance.ai.agent.api.AgentStreamEvent;
import com.bytedance.ai.agent.api.AgentTurnFacade;
import com.bytedance.ai.agent.api.AgentTurnRequest;
import com.bytedance.ai.agent.api.events.AnswerDeltaPayload;
import com.bytedance.ai.agent.api.events.TurnCompletedPayload;
import com.bytedance.ai.agent.api.events.TurnStartedPayload;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTurnControllerTests {

    @Test
    void turnEndpointStreamsServerSentEvents() {
        WebTestClient client = WebTestClient
                .bindToController(new AgentTurnController(new FixedAgentTurnFacade()))
                .build();

        List<ServerSentEvent<String>> events = client.post()
                .uri("/public/agent/turn")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {"userId":"u1","conversationId":"c1","message":"推荐 300 元以下的双肩包","turnId":"t1"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .getResponseBody()
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("turn.started", "answer.delta", "turn.completed");
        assertThat(events).extracting(ServerSentEvent::comment).containsOnly("corr-1");
    }

    @Test
    void turnEndpointRejectsInvalidRequest() {
        WebTestClient client = WebTestClient
                .bindToController(new AgentTurnController(new FixedAgentTurnFacade()))
                .build();

        client.post()
                .uri("/public/agent/turn")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {"userId":"u1","conversationId":"c1","message":""}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }

    private static final class FixedAgentTurnFacade implements AgentTurnFacade {
        @Override
        public Flux<AgentStreamEvent> turnStream(AgentTurnRequest request) {
            return Flux.just(
                    new AgentStreamEvent("1", "turn.started", "corr-1",
                            new TurnStartedPayload(request.turnId(), request.conversationId(), "agent-answer-v1")),
                    new AgentStreamEvent("2", "answer.delta", "corr-1", new AnswerDeltaPayload("ok")),
                    new AgentStreamEvent("3", "turn.completed", "corr-1",
                            new TurnCompletedPayload(request.turnId(), 10, null, null, false))
            );
        }
    }
}
