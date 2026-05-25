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
    void getTurnEndpointStreamsServerSentEventsForSseClients() {
        WebTestClient client = WebTestClient
                .bindToController(new AgentTurnController(new FixedAgentTurnFacade()))
                .build();

        List<ServerSentEvent<String>> events = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/public/agent/turn")
                        .queryParam("userId", "u1")
                        .queryParam("conversationId", "c1")
                        .queryParam("message", "推荐防晒霜")
                        .queryParam("turnId", "t-get-1")
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
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
        assertThat(events.getFirst().data()).contains("t-get-1");
    }

    @Test
    void turnEndpointRejectsInvalidRequest() {
        WebTestClient client = WebTestClient
                .bindToController(new AgentTurnController(new FixedAgentTurnFacade()))
                .build();

        // Controller 现在是 GET + @RequestParam，缺失必填参数 message 时必须以 400 拒绝。
        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/public/agent/turn")
                        .queryParam("userId", "u1")
                        .queryParam("conversationId", "c1")
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
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
