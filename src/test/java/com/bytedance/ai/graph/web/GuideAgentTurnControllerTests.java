package com.bytedance.ai.graph.web;

import com.bytedance.ai.graph.api.AgentStreamEvent;
import com.bytedance.ai.graph.api.AgentStreamEventType;
import com.bytedance.ai.graph.api.GuideGraphFinalSummary;
import com.bytedance.ai.graph.api.GuideGraphIntent;
import com.bytedance.ai.graph.api.GuideGraphRequest;
import com.bytedance.ai.graph.api.GuideGraphStreamFacade;
import com.bytedance.ai.graph.api.NodeRunStatus;
import com.bytedance.ai.graph.api.events.TurnErrorPayload;
import com.bytedance.ai.graph.api.events.TurnStartedPayload;
import com.bytedance.ai.graph.conversation.ConversationMessage;
import com.bytedance.ai.graph.conversation.persistence.AgentConversationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GuideAgentTurnControllerTests {

    @Test
    void getTurnEndpointStreamsServerSentEventsForSseClients() {
        CapturingGuideGraphStreamFacade facade = new CapturingGuideGraphStreamFacade();
        WebTestClient client = WebTestClient
                .bindToController(new GuideAgentTurnController(facade, stubRepo()))
                .build();

        List<ServerSentEvent<String>> events = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/public/agent/turn")
                        .queryParam("userId", "u1")
                        .queryParam("conversationId", "c1")
                        .queryParam("message", "推荐防晒霜")
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
                .containsExactly(
                        AgentStreamEventType.TURN_STARTED.eventName(),
                        AgentStreamEventType.TURN_COMPLETED.eventName()
        );
        assertThat(events.getFirst().data()).contains("turn_1_");
        assertThat(events.getFirst().data()).contains("requestId");
        assertThat(events.getLast().data()).contains("SUCCESS");
        assertThat(events.getLast().data()).doesNotContain("finalNode");
        assertThat(facade.lastRequest.get()).isNotNull();
        assertThat(facade.lastRequest.get().runId()).startsWith("turn_1_");
        assertThat(facade.lastRequest.get().requestId()).isNotBlank();
        assertThat(facade.lastRequest.get().initialIntent()).isNull();
    }

    @Test
    void getTurnEndpointTraceModeKeepsNodeLifecycleEvents() {
        CapturingGuideGraphStreamFacade facade = new CapturingGuideGraphStreamFacade();
        WebTestClient client = WebTestClient
                .bindToController(new GuideAgentTurnController(facade, stubRepo()))
                .build();

        List<ServerSentEvent<String>> events = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/public/agent/turn")
                        .queryParam("userId", "u1")
                        .queryParam("conversationId", "c1")
                        .queryParam("message", "推荐防晒霜")
                        .queryParam("streamMode", "trace")
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .getResponseBody()
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly(
                        AgentStreamEventType.TURN_STARTED.eventName(),
                        AgentStreamEventType.NODE_STARTED.eventName(),
                        AgentStreamEventType.NODE_COMPLETED.eventName(),
                        AgentStreamEventType.TURN_COMPLETED.eventName()
                );
        assertThat(events.getLast().data()).contains("finalNode");
    }

    @Test
    void getTurnEndpointGeneratesMissingTurnIdAndRequestId() {
        CapturingGuideGraphStreamFacade facade = new CapturingGuideGraphStreamFacade();
        WebTestClient client = WebTestClient
                .bindToController(new GuideAgentTurnController(facade, stubRepo()))
                .build();

        List<ServerSentEvent<String>> events = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/public/agent/turn")
                        .queryParam("userId", "u1")
                        .queryParam("conversationId", "c1")
                        .queryParam("message", "继续推荐")
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .getResponseBody()
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly(
                        AgentStreamEventType.TURN_STARTED.eventName(),
                        AgentStreamEventType.TURN_COMPLETED.eventName()
                );
        assertThat(facade.lastRequest.get()).isNotNull();
        assertThat(facade.lastRequest.get().runId()).isNotBlank();
        assertThat(facade.lastRequest.get().requestId()).isNotBlank();
        assertThat(facade.lastRequest.get().runId()).isNotEqualTo("c1");
        assertThat(facade.lastRequest.get().requestId()).isNotEqualTo("c1");
        assertThat(facade.lastRequest.get().initialIntent()).isNull();
    }

    @Test
    void getTurnEndpointUsesServerGeneratedTurnIdAndPreservesClientRequestId() {
        CapturingGuideGraphStreamFacade facade = new CapturingGuideGraphStreamFacade();
        WebTestClient client = WebTestClient
                .bindToController(new GuideAgentTurnController(facade, stubRepo()))
                .build();

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/public/agent/turn")
                        .queryParam("userId", "u1")
                        .queryParam("conversationId", "c1")
                        .queryParam("message", "这个多少钱")
                        .queryParam("requestId", "req-456")
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .getResponseBody()
                .collectList()
                .block();

        assertThat(facade.lastRequest.get()).isNotNull();
        assertThat(facade.lastRequest.get().runId()).startsWith("turn_1_");
        assertThat(facade.lastRequest.get().requestId()).isEqualTo("req-456");
        assertThat(facade.lastRequest.get().correlationId()).isEqualTo("req-456");
    }

    @Test
    void controllerDoesNotSeedProductSearchForPriceQuestion() {
        CapturingGuideGraphStreamFacade facade = new CapturingGuideGraphStreamFacade();
        WebTestClient client = WebTestClient
                .bindToController(new GuideAgentTurnController(facade, stubRepo()))
                .build();

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/public/agent/turn")
                        .queryParam("userId", "u1")
                        .queryParam("conversationId", "c1")
                        .queryParam("message", "这个多少钱")
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .getResponseBody()
                .collectList()
                .block();

        assertThat(facade.lastRequest.get()).isNotNull();
        assertThat(facade.lastRequest.get().message()).isEqualTo("这个多少钱");
        assertThat(facade.lastRequest.get().runId()).isNotBlank();
        assertThat(facade.lastRequest.get().requestId()).isNotBlank();
        assertThat(facade.lastRequest.get().initialIntent()).isNull();
    }

    @Test
    void controllerSourceDoesNotContainProductSearchHardCoding() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/bytedance/ai/graph/web/GuideAgentTurnController.java"
        ));

        assertThat(source).doesNotContain("PRODUCT_SEARCH");
    }

    @Test
    void turnEndpointRejectsInvalidRequest() {
        WebTestClient client = WebTestClient
                .bindToController(new GuideAgentTurnController(new CapturingGuideGraphStreamFacade(), stubRepo()))
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

    @Test
    void prodModeMapsTurnErrorToPublicErrorEvent() {
        GuideGraphStreamFacade facade = request -> {
            GuideGraphFinalSummary summary = GuideGraphFinalSummary.failed(
                    request,
                    GuideGraphIntent.CLARIFY,
                    "clarify_workflow",
                    "main_intent_router",
                    "MAIN_INTENT_LLM_TIMEOUT",
                    "java.net.SocketTimeoutException: intent service timeout"
            );
            return Flux.just(
                    new AgentStreamEvent("1", AgentStreamEventType.TURN_STARTED.eventName(), request.correlationId(),
                            new TurnStartedPayload(request.runId(), request.conversationId(), "guide-state-graph-v1")),
                    new AgentStreamEvent("3", AgentStreamEventType.TURN_ERROR.eventName(), request.correlationId(),
                            new TurnErrorPayload("MAIN_INTENT_LLM_TIMEOUT", "java.net.SocketTimeoutException", true)),
                    new AgentStreamEvent("4", AgentStreamEventType.TURN_COMPLETED.eventName(), request.correlationId(), summary)
            );
        };
        WebTestClient client = WebTestClient
                .bindToController(new GuideAgentTurnController(facade, stubRepo()))
                .build();

        List<ServerSentEvent<String>> events = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/public/agent/turn")
                        .queryParam("userId", "u1")
                        .queryParam("conversationId", "c1")
                        .queryParam("message", "这个帮我处理一下")
                        .queryParam("requestId", "req-timeout")
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .getResponseBody()
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("turn.started", "error", "turn.completed");
        assertThat(events.get(1).data())
                .contains("MAIN_INTENT_LLM_TIMEOUT")
                .contains("当前请求处理超时，请稍后重试。")
                .contains("req-timeout")
                .doesNotContain("SocketTimeoutException");
        assertThat(events.getLast().data())
                .contains("FAILED")
                .contains("MAIN_INTENT_LLM_TIMEOUT")
                .doesNotContain("errorMessage");
    }

    private static AgentConversationRepository stubRepo() {
        AtomicLong seq = new AtomicLong();
        return new AgentConversationRepository() {
            @Override
            public boolean existsConversation(String userId, String conversationId) {
                return true;
            }

            @Override
            public Long initConversation(String userId, String conversationId) {
                return 1L;
            }

            @Override
            public String allocateTurnId(String userId, String conversationId) {
                return String.format("turn_1_%06d", seq.incrementAndGet());
            }

            @Override
            public List<ConversationMessage> loadRecentMessages(String userId, String conversationId, int limit) {
                return List.of();
            }

            @Override
            public ConversationMessage saveUserMessage(String u, String c, String t, String r, String m) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ConversationMessage saveAssistantMessage(String u, String c, String t, String r, String m, String s) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void createOrUpdateTurn(String u, String c, String t, String r, String s, String i, String w) {
            }
        };
    }

    private static final class CapturingGuideGraphStreamFacade implements GuideGraphStreamFacade {

        private final AtomicReference<GuideGraphRequest> lastRequest = new AtomicReference<>();

        @Override
        public Flux<AgentStreamEvent> turnStream(GuideGraphRequest request) {
            lastRequest.set(request);
            GuideGraphFinalSummary summary = new GuideGraphFinalSummary(
                    request.runId(),
                    request.requestId(),
                    request.correlationId(),
                    request.conversationId(),
                    request.userId(),
                    GuideGraphIntent.CLARIFY,
                    "clarify_workflow",
                    NodeRunStatus.SUCCESS,
                    "build_answer_context",
                    null,
                    null,
                    java.time.Instant.now()
            );
            return Flux.just(
                    new AgentStreamEvent("1", AgentStreamEventType.TURN_STARTED.eventName(), request.correlationId(),
                            new TurnStartedPayload(request.runId(), request.conversationId(), "guide-state-graph-v1")),
                    new AgentStreamEvent("2", AgentStreamEventType.NODE_STARTED.eventName(), request.correlationId(),
                            java.util.Map.of("nodeName", "load_conversation_context")),
                    new AgentStreamEvent("3", AgentStreamEventType.NODE_COMPLETED.eventName(), request.correlationId(),
                            java.util.Map.of("nodeName", "load_conversation_context")),
                    new AgentStreamEvent("4", AgentStreamEventType.TURN_COMPLETED.eventName(), request.correlationId(), summary)
            );
        }
    }
}
