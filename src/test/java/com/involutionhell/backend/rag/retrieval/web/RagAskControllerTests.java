package com.involutionhell.backend.rag.retrieval.web;

import com.involutionhell.backend.rag.retrieval.api.RagAnswerDeltaView;
import com.involutionhell.backend.rag.retrieval.api.RagAskCompletedView;
import com.involutionhell.backend.rag.retrieval.api.RagAskFacade;
import com.involutionhell.backend.rag.retrieval.api.RagAskRequest;
import com.involutionhell.backend.rag.retrieval.api.RagAskStartedView;
import com.involutionhell.backend.rag.retrieval.api.RagAskStreamEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

class RagAskControllerTests {

    @Test
    void askEndpointStreamsServerSentEvents() {
        WebTestClient client = WebTestClient
                .bindToController(new RagAskController(new FixedAskFacade()))
                .build();

        List<ServerSentEvent<String>> events = client.post()
                .uri("/public/rag/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {"userId":"user-001","conversationId":"conv-001","question":"怎么使用 RAG?","topK":3,"tags":[],"history":[]}
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
                .containsExactly("started", "answer_delta", "completed");
    }

    @Test
    void askEndpointRejectsInvalidRequest() {
        WebTestClient client = WebTestClient
                .bindToController(new RagAskController(new FixedAskFacade()))
                .build();

        client.post()
                .uri("/public/rag/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {"userId":"user-001","conversationId":"conv-001","question":"","topK":3}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void askEndpointRejectsMissingUserIdAndConversationId() {
        WebTestClient client = WebTestClient
                .bindToController(new RagAskController(new FixedAskFacade()))
                .build();

        client.post()
                .uri("/public/rag/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {"question":"问题","topK":3}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }

    private static final class FixedAskFacade implements RagAskFacade {

        @Override
        public Flux<RagAskStreamEvent> askStream(RagAskRequest request) {
            String correlationId = "ask:test";
            return Flux.just(
                    new RagAskStreamEvent(
                            "1",
                            "started",
                            correlationId,
                            new RagAskStartedView(correlationId, request.question(), request.topK())
                    ),
                    new RagAskStreamEvent(
                            "2",
                            "answer_delta",
                            correlationId,
                            new RagAnswerDeltaView("ok")
                    ),
                    new RagAskStreamEvent(
                            "3",
                            "completed",
                            correlationId,
                            new RagAskCompletedView(false, false, List.of(), 0)
                    )
            );
        }
    }
}
