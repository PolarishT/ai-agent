package com.bytedance.ai.agent.application;

import com.bytedance.ai.agent.answer.AgentAnswerGenerator;
import com.bytedance.ai.agent.answer.CitationExtractor;
import com.bytedance.ai.agent.api.AgentStreamEvent;
import com.bytedance.ai.agent.api.AgentTurnRequest;
import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.persistence.AgentTurnPersistenceService;
import com.bytedance.ai.agent.persistence.AgentTurnRecord;
import com.bytedance.ai.agent.persistence.AgentTurnRepository;
import com.bytedance.ai.agent.slot.SlotExtractor;
import com.bytedance.ai.agent.tool.ToolRegistry;
import com.bytedance.ai.agent.tool.impl.SearchProductsToolCallback;
import com.bytedance.ai.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.catalog.api.CatalogSkuView;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import com.bytedance.ai.retrieval.spi.AgentConversationSpi;
import com.bytedance.ai.retrieval.spi.AgentTurnConversationState;
import com.bytedance.ai.retrieval.spi.ProductSearchHit;
import com.bytedance.ai.retrieval.spi.ProductSearchRequest;
import com.bytedance.ai.retrieval.spi.ProductSearchSpi;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTurnServiceTests {

    private final RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());

    @Test
    void emitsToolCardsAnswerCitationAndCompleted() {
        InMemoryAgentTurnRepository repository = new InMemoryAgentTurnRepository();
        AgentTurnService service = service(repository, "推荐 300 元以下的双肩包");

        List<AgentStreamEvent> events = service.turnStream(new AgentTurnRequest(
                "u1",
                "c1",
                "推荐 300 元以下的双肩包",
                "turn-1",
                null,
                null,
                null
        )).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::event).containsSubsequence(
                "turn.started",
                "intent.detected",
                "tool.calling",
                "tool.result",
                "answer.delta",
                "citation",
                "turn.completed"
        );
        AgentTurnRecord record = repository.findByTurnId("turn-1").orElseThrow();
        assertThat(record.status()).isEqualTo("SUCCEEDED");
        assertThat(record.intent()).isEqualTo("FILTER_BY_ATTR");
        assertThat(record.answerText()).contains("[#1]");
        assertThat(record.cardsEmitted()).contains("轻便双肩包");
    }

    @Test
    void outOfScopeSkipsToolsAndStillCompletes() {
        InMemoryAgentTurnRepository repository = new InMemoryAgentTurnRepository();
        AgentTurnService service = service(repository, "帮我写代码");

        List<AgentStreamEvent> events = service.turnStream(new AgentTurnRequest(
                "u1",
                "c1",
                "帮我写代码",
                "turn-oos",
                null,
                null,
                null
        )).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::event)
                .containsExactly("turn.started", "intent.detected", "answer.delta", "turn.completed");
        AgentTurnRecord record = repository.findByTurnId("turn-oos").orElseThrow();
        assertThat(record.intent()).isEqualTo("OUT_OF_SCOPE");
        assertThat(record.status()).isEqualTo("SUCCEEDED");
    }

    private AgentTurnService service(InMemoryAgentTurnRepository repository, String message) {
        AgentTurnPersistenceService persistenceService = new AgentTurnPersistenceService(repository, jsonCodec);
        ConversationTurnAdapter conversationTurnAdapter = new ConversationTurnAdapter(new StubConversationSpi());
        SlotExtractor slotExtractor = (ignored, intent) -> intent == IntentType.FILTER_BY_ATTR
                ? new Slot(List.of("轻便"), List.of(), new Slot.PriceRange(null, new BigDecimal("300")), "箱包", List.of(), null)
                : Slot.empty();
        SearchProductsToolCallback searchProductsTool = new SearchProductsToolCallback(
                new StubProductSearchSpi(),
                new StubCatalogQueryFacade(),
                jsonCodec
        );
        return new AgentTurnService(
                persistenceService,
                conversationTurnAdapter,
                new com.bytedance.ai.agent.intent.RuleBasedIntentClassifier(),
                slotExtractor,
                new ToolRegistry(List.of(searchProductsTool)),
                new AgentAnswerGenerator(noChatModel(), new ClassPathResource("prompts/agent-answer-v1.txt")),
                new CitationExtractor(),
                new AgentSseEventFactory(),
                jsonCodec,
                Schedulers.immediate()
        );
    }

    private static ObjectProvider<ChatModel> noChatModel() {
        return new ObjectProvider<>() {
            @Override
            public ChatModel getObject(Object... args) throws BeansException {
                return null;
            }

            @Override
            public ChatModel getIfAvailable() throws BeansException {
                return null;
            }

            @Override
            public ChatModel getIfUnique() throws BeansException {
                return null;
            }

            @Override
            public ChatModel getObject() throws BeansException {
                return null;
            }
        };
    }

    private static class StubConversationSpi implements AgentConversationSpi {
        @Override
        public AgentTurnConversationState beginTurn(String userId, String conversationId, String userMessage, String correlationId) {
            return new AgentTurnConversationState(1L, "user-msg-1", "assistant-msg-1", List.of());
        }

        @Override
        public void completeTurn(String assistantMessageId, String answerText) {
        }

        @Override
        public void failTurn(String assistantMessageId, String errorCode, String errorMessage) {
        }
    }

    private static class StubProductSearchSpi implements ProductSearchSpi {
        @Override
        public List<ProductSearchHit> search(ProductSearchRequest request) {
            return List.of(new ProductSearchHit(9L, 99L, "SPU-9", 0.9d, "TITLE", "匹配轻便", Map.of()));
        }
    }

    private static class StubCatalogQueryFacade implements CatalogQueryFacade {
        @Override
        public CatalogSpuView getSpu(Long spuId) {
            return spu();
        }

        @Override
        public Optional<CatalogSpuView> findSpuByExternalRef(String externalRef) {
            return Optional.of(spu());
        }

        @Override
        public List<CatalogSkuView> listSkus(Long spuId) {
            return List.of();
        }

        private CatalogSpuView spu() {
            return new CatalogSpuView(
                    9L,
                    "SPU-9",
                    "轻便双肩包",
                    "Acme",
                    "箱包",
                    new BigDecimal("199"),
                    new BigDecimal("199"),
                    8,
                    null,
                    List.of(),
                    null,
                    Map.of(),
                    "DONE",
                    "ACTIVE",
                    99L,
                    List.of(),
                    OffsetDateTime.now(),
                    OffsetDateTime.now()
            );
        }
    }

    private class InMemoryAgentTurnRepository implements AgentTurnRepository {
        private final Map<String, AgentTurnRecord> records = new LinkedHashMap<>();

        @Override
        public void createRunning(String turnId, String correlationId, String userId, String conversationId, String requestId, String userMessage) {
            records.put(turnId, new AgentTurnRecord(
                    (long) records.size() + 1,
                    turnId,
                    correlationId,
                    userId,
                    conversationId,
                    requestId,
                    null,
                    null,
                    "RUNNING",
                    userMessage,
                    null,
                    null,
                    null,
                    "{}",
                    "[]",
                    "[]",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    OffsetDateTime.now(),
                    null
            ));
        }

        @Override
        public Optional<AgentTurnRecord> findByTurnId(String turnId) {
            return Optional.ofNullable(records.get(turnId));
        }

        @Override
        public Optional<AgentTurnRecord> findByRequestId(String userId, String conversationId, String requestId) {
            return records.values().stream()
                    .filter(record -> record.userId().equals(userId)
                            && record.conversationId().equals(conversationId)
                            && requestId.equals(record.requestId()))
                    .findFirst();
        }

        @Override
        public List<AgentTurnRecord> findRecentByConversationId(String conversationId, int limit) {
            return new ArrayList<>(records.values());
        }

        @Override
        public void attachConversationMessages(String turnId, String userMessageId, String assistantMessageId) {
            AgentTurnRecord r = records.get(turnId);
            records.put(turnId, copy(r, userMessageId, assistantMessageId, r.status(), r.intent(), r.intentSource(),
                    r.intentConfidence(), r.slotsJson(), r.toolsCalled(), r.cardsEmitted(), r.generatedByModel(),
                    r.answerText(), r.latencyMs(), r.errorCode(), r.errorMessage(), r.completedAt()));
        }

        @Override
        public void recordIntent(String turnId, String intent, String source, Double confidence, String slotsJson) {
            AgentTurnRecord r = records.get(turnId);
            records.put(turnId, copy(r, r.userMessageId(), r.assistantMessageId(), r.status(), intent, source,
                    confidence, slotsJson, r.toolsCalled(), r.cardsEmitted(), r.generatedByModel(), r.answerText(),
                    r.latencyMs(), r.errorCode(), r.errorMessage(), r.completedAt()));
        }

        @Override
        public void recordToolState(String turnId, String toolsCalledJson, String cardsEmittedJson) {
            AgentTurnRecord r = records.get(turnId);
            records.put(turnId, copy(r, r.userMessageId(), r.assistantMessageId(), r.status(), r.intent(),
                    r.intentSource(), r.intentConfidence(), r.slotsJson(), toolsCalledJson, cardsEmittedJson,
                    r.generatedByModel(), r.answerText(), r.latencyMs(), r.errorCode(), r.errorMessage(), r.completedAt()));
        }

        @Override
        public void markSucceeded(String turnId, String answerText, Boolean generatedByModel, Integer tokensIn, Integer tokensOut, Integer latencyMs) {
            AgentTurnRecord r = records.get(turnId);
            records.put(turnId, copy(r, r.userMessageId(), r.assistantMessageId(), "SUCCEEDED", r.intent(),
                    r.intentSource(), r.intentConfidence(), r.slotsJson(), r.toolsCalled(), r.cardsEmitted(),
                    generatedByModel, answerText, latencyMs, null, null, OffsetDateTime.now()));
        }

        @Override
        public void markFailed(String turnId, String errorCode, String errorMessage, Integer latencyMs) {
            AgentTurnRecord r = records.get(turnId);
            records.put(turnId, copy(r, r.userMessageId(), r.assistantMessageId(), "FAILED", r.intent(),
                    r.intentSource(), r.intentConfidence(), r.slotsJson(), r.toolsCalled(), r.cardsEmitted(),
                    r.generatedByModel(), r.answerText(), latencyMs, errorCode, errorMessage, OffsetDateTime.now()));
        }

        private AgentTurnRecord copy(
                AgentTurnRecord r,
                String userMessageId,
                String assistantMessageId,
                String status,
                String intent,
                String intentSource,
                Double intentConfidence,
                String slotsJson,
                String toolsCalled,
                String cardsEmitted,
                Boolean generatedByModel,
                String answerText,
                Integer latencyMs,
                String errorCode,
                String errorMessage,
                OffsetDateTime completedAt
        ) {
            return new AgentTurnRecord(
                    r.id(), r.turnId(), r.correlationId(), r.userId(), r.conversationId(), r.requestId(),
                    userMessageId, assistantMessageId, status, r.userMessage(), intent, intentSource,
                    intentConfidence, slotsJson, toolsCalled, cardsEmitted, generatedByModel, answerText,
                    r.tokensIn(), r.tokensOut(), latencyMs, errorCode, errorMessage, r.startedAt(), completedAt
            );
        }
    }
}
