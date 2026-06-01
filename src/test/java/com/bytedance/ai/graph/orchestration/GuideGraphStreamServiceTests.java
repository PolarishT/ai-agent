package com.bytedance.ai.graph.orchestration;

import com.bytedance.ai.graph.api.AgentStreamEvent;
import com.bytedance.ai.graph.api.AgentTurnRequest;
import com.bytedance.ai.graph.api.AgentStreamEventType;
import com.bytedance.ai.graph.api.GuideGraphFinalSummary;
import com.bytedance.ai.graph.api.GuideGraphIntent;
import com.bytedance.ai.graph.api.GuideGraphRequest;
import com.bytedance.ai.graph.api.GuideNodeExecutionResult;
import com.bytedance.ai.graph.api.NodeRunStatus;
import com.bytedance.ai.graph.cartmanage.application.CartCommandService;
import com.bytedance.ai.graph.cartmanage.CartManageAction;
import com.bytedance.ai.graph.cartmanage.application.CartManageSlotFillingService;
import com.bytedance.ai.graph.cartmanage.CartManageSlots;
import com.bytedance.ai.graph.cartmanage.application.CartManageWorkflowNode;
import com.bytedance.ai.graph.cartmanage.CartMutationResult;
import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import com.bytedance.ai.graph.cartmanage.application.ProductCatalogResolver;
import com.bytedance.ai.graph.cartmanage.StockResult;
import com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys;
import com.bytedance.ai.graph.conversation.persistence.AgentConversationRepository;
import com.bytedance.ai.graph.conversation.ConversationMessage;
import com.bytedance.ai.graph.intent.MainIntentDecision;
import com.bytedance.ai.graph.intent.service.MainIntentRouterService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class GuideGraphStreamServiceTests {

    private final StubRepo repo = new StubRepo();
    private final GuideGraphStreamService service = new GuideGraphStreamService(new GuideStateGraphFactory(repo), repo);

    @Test
    void streamsNodeLifecycleEventsFinalSummaryAndAnswerDelta() {
        GuideGraphRequest request = new GuideGraphRequest(
                "u1",
                "c1",
                "查库存",
                "run-1",
                "req-1",
                null,
                "corr-1",
                GuideGraphIntent.INVENTORY_QUERY,
                List.of()
        );

        List<AgentStreamEvent> events = service.turnStream(request).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::event)
                .contains(AgentStreamEventType.ANSWER_DELTA.eventName());
        assertThat(events.getFirst().event()).isEqualTo(AgentStreamEventType.TURN_STARTED.eventName());
        assertThat(events.getLast().event()).isEqualTo(AgentStreamEventType.TURN_COMPLETED.eventName());
        assertThat(events).extracting(AgentStreamEvent::event)
                .contains(AgentStreamEventType.NODE_STARTED.eventName(), AgentStreamEventType.NODE_COMPLETED.eventName());

        GuideGraphFinalSummary summary = (GuideGraphFinalSummary) events.getLast().data();
        assertThat(summary.runId()).isEqualTo("run-1");
        assertThat(summary.conversationId()).isEqualTo("c1");
        assertThat(summary.intent()).isEqualTo(GuideGraphIntent.INVENTORY_QUERY);
        assertThat(summary.targetWorkflow()).isEqualTo(GuideGraphNodeNames.INVENTORY_QUERY_WORKFLOW);
        assertThat(summary.status()).isEqualTo(NodeRunStatus.SUCCESS);
        assertThat(summary.finalNode()).isEqualTo(GuideGraphNodeNames.BUILD_ANSWER_CONTEXT);
    }

    @Test
    void cartManageAddClarificationIsStreamedAsAnswerDelta() {
        StubRepo cartRepo = new StubRepo();
        CartManageWorkflowNode cartNode = new CartManageWorkflowNode(
                new StubSlotFilling(new CartManageSlots(
                        CartManageAction.ADD, null, "14 寸防水轻量通勤双肩包",
                        null, null, null, false, "stub")),
                (userId, conversationId) -> null,
                new StubCartCommand(),
                (productId, skuId, requestedQuantity) -> StockResult.inStock(productId, skuId, requestedQuantity),
                (productName, limit) -> List.of(
                        new ProductCandidate("101", "201", "轻量通勤双肩包 14 寸",
                                new BigDecimal("99.00"), "brand", "黑色", "SPU-101"),
                        new ProductCandidate("102", "202", "防水电脑双肩包 14 寸",
                                new BigDecimal("129.00"), "brand", "蓝色", "SPU-102")
                )
        );
        GuideGraphStreamService cartService =
                new GuideGraphStreamService(new GuideStateGraphFactory(cartRepo, cartNode), cartRepo);
        GuideGraphRequest request = new GuideGraphRequest(
                "u-cart",
                "c-cart",
                "帮我把 14 寸防水轻量通勤双肩包加入购物车",
                "run-cart-1",
                "req-cart-1",
                null,
                "corr-cart-1",
                GuideGraphIntent.CART_MANAGE,
                List.of()
        );

        List<AgentStreamEvent> events = cartService.turnStream(request).collectList().block();

        assertThat(events).isNotNull();
        AgentStreamEvent answer = events.stream()
                .filter(event -> AgentStreamEventType.ANSWER_DELTA.eventName().equals(event.event()))
                .findFirst()
                .orElseThrow();
        assertThat(String.valueOf(answer.data())).contains("请选择要加入购物车的商品");
    }

    @Test
    void fromAgentTurnRequestUsesTurnIdAsRunIdAndDoesNotSeedIntent() {
        AgentTurnRequest turnRequest = new AgentTurnRequest(
                "u1",
                "c1",
                "推荐防晒霜",
                "client-turn-id",
                "req-1",
                null,
                List.of()
        );

        GuideGraphRequest graphRequest = GuideGraphRequest.from(turnRequest);

        assertThat(graphRequest.runId()).isNotBlank();
        assertThat(graphRequest.runId()).isEqualTo("client-turn-id");
        assertThat(graphRequest.requestId()).isEqualTo("req-1");
        assertThat(graphRequest.correlationId()).isEqualTo("req-1");
        assertThat(graphRequest.initialIntent()).isNull();
    }

    @Test
    void nodeFailureCompletesGraphWithFailedFinalSummary() {
        StubRepo failingRepo = new StubRepo();
        GuideGraphStreamService failingService = new GuideGraphStreamService(new GuideStateGraphFactory(failingRepo) {
            @Override
            public com.alibaba.cloud.ai.graph.CompiledGraph compile(java.util.function.Consumer<AgentStreamEvent> eventSink) {
                return compile(eventSink, Map.of(
                        GuideGraphNodeNames.PRODUCT_QUERY_WORKFLOW,
                        state -> {
                            throw new IllegalStateException("product query workflow failed");
                        }
                ));
            }
        }, failingRepo);
        GuideGraphRequest request = new GuideGraphRequest(
                "u1",
                "c1",
                "搜索洗面奶",
                "run-fail-1",
                "req-fail-1",
                null,
                "corr-fail-1",
                GuideGraphIntent.PRODUCT_SEARCH,
                List.of()
        );

        List<AgentStreamEvent> events = failingService.turnStream(request).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::event)
                .contains(AgentStreamEventType.NODE_FAILED.eventName())
                .contains(AgentStreamEventType.TURN_ERROR.eventName())
                .doesNotContain(AgentStreamEventType.ANSWER_DELTA.eventName());
        assertThat(events.getLast().event()).isEqualTo(AgentStreamEventType.TURN_COMPLETED.eventName());

        GuideGraphFinalSummary summary = (GuideGraphFinalSummary) events.getLast().data();
        assertThat(summary.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(summary.finalNode()).isEqualTo(GuideGraphNodeNames.PRODUCT_QUERY_WORKFLOW);
        assertThat(summary.errorCode()).isEqualTo("GUIDE_GRAPH_NODE_FAILED");
        assertThat(summary.errorMessage()).isEqualTo("product query workflow failed");
    }

    @Test
    void mainIntentRouterTimeoutStreamsErrorAndFailedTurn() {
        StubRepo timeoutRepo = new StubRepo();
        GuideGraphStreamService timeoutService = new GuideGraphStreamService(
                new GuideStateGraphFactory(
                        timeoutRepo,
                        new TimeoutMainIntentRouterService(),
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                timeoutRepo
        );
        GuideGraphRequest request = new GuideGraphRequest(
                "u-timeout",
                "c-timeout",
                "这个帮我处理一下",
                "run-timeout",
                "req-timeout",
                null,
                "corr-timeout",
                null,
                List.of()
        );

        List<AgentStreamEvent> events = timeoutService.turnStream(request).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::event)
                .contains(AgentStreamEventType.TURN_ERROR.eventName())
                .doesNotContain(AgentStreamEventType.ANSWER_DELTA.eventName());
        AgentStreamEvent error = events.stream()
                .filter(event -> AgentStreamEventType.TURN_ERROR.eventName().equals(event.event()))
                .findFirst()
                .orElseThrow();
        assertThat(String.valueOf(error.data()))
                .contains("MAIN_INTENT_LLM_TIMEOUT")
                .contains("当前请求处理超时，请稍后重试。");

        GuideGraphFinalSummary summary = (GuideGraphFinalSummary) events.getLast().data();
        assertThat(summary.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(summary.finalNode()).isEqualTo(GuideGraphNodeNames.MAIN_INTENT_ROUTER);
        assertThat(summary.errorCode()).isEqualTo("MAIN_INTENT_LLM_TIMEOUT");
        assertThat(summary.intent()).isEqualTo(GuideGraphIntent.CLARIFY);
        assertThat(summary.targetWorkflow()).isEqualTo(GuideGraphNodeNames.CLARIFY_WORKFLOW);
    }

    @Test
    void cartNodeMessageIsStreamedWhenAnswerContextIsMissing() {
        StubRepo cartRepo = new StubRepo();
        String nodeMessage = "我找到几款可能符合的商品，请选择要加入购物车的商品：";
        GuideGraphStreamService cartService = new GuideGraphStreamService(new GuideStateGraphFactory(cartRepo) {
            @Override
            public com.alibaba.cloud.ai.graph.CompiledGraph compile(java.util.function.Consumer<AgentStreamEvent> eventSink) {
                return compile(eventSink, Map.of(
                        GuideGraphNodeNames.CART_MANAGE_WORKFLOW,
                        state -> GuideNodeExecutionResult.withStateUpdates(
                                Map.of(CartGraphStateKeys.NODE_MESSAGE, nodeMessage),
                                Map.of("cartMessageReady", true)
                        ),
                        GuideGraphNodeNames.BUILD_ANSWER_CONTEXT,
                        state -> GuideNodeExecutionResult.withStateUpdates(Map.of(), Map.of("answerContextSkipped", true))
                ));
            }
        }, cartRepo);
        GuideGraphRequest request = new GuideGraphRequest(
                "u-cart",
                "c-cart",
                "帮我加一个轻量通勤双肩包",
                "run-cart-fallback",
                "req-cart-fallback",
                null,
                "corr-cart-fallback",
                GuideGraphIntent.CART_MANAGE,
                List.of()
        );

        List<AgentStreamEvent> events = cartService.turnStream(request).collectList().block();

        assertThat(events).isNotNull();
        AgentStreamEvent answer = events.stream()
                .filter(event -> AgentStreamEventType.ANSWER_DELTA.eventName().equals(event.event()))
                .findFirst()
                .orElseThrow();
        assertThat(String.valueOf(answer.data())).contains(nodeMessage);
        assertThat(cartRepo.assistantMessages()).extracting(ConversationMessage::content)
                .contains(nodeMessage);
    }

    @Test
    void graphExceptionStreamsErrorAndFailedTurnCompleted() {
        StubRepo failingRepo = new StubRepo();
        GuideGraphStreamService failingService = new GuideGraphStreamService(new GuideStateGraphFactory(failingRepo) {
            @Override
            public com.alibaba.cloud.ai.graph.CompiledGraph compile(java.util.function.Consumer<AgentStreamEvent> eventSink) {
                throw new IllegalStateException("compiled graph unavailable");
            }
        }, failingRepo);
        GuideGraphRequest request = new GuideGraphRequest(
                "u-cart",
                "c-cart",
                "帮我加一个轻量通勤双肩包",
                "run-cart-error",
                "req-cart-error",
                null,
                "corr-cart-error",
                GuideGraphIntent.CART_MANAGE,
                List.of()
        );

        List<AgentStreamEvent> events = failingService.turnStream(request).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::event)
                .contains(AgentStreamEventType.TURN_ERROR.eventName(), AgentStreamEventType.TURN_COMPLETED.eventName())
                .doesNotContain(AgentStreamEventType.ANSWER_DELTA.eventName());
        assertThat(String.valueOf(events.stream()
                .filter(event -> AgentStreamEventType.TURN_ERROR.eventName().equals(event.event()))
                .findFirst()
                .orElseThrow()
                .data())).contains("当前请求处理失败，请稍后重试。");
        assertThat(events.getLast().event()).isEqualTo(AgentStreamEventType.TURN_COMPLETED.eventName());
        GuideGraphFinalSummary summary = (GuideGraphFinalSummary) events.getLast().data();
        assertThat(summary.status()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(summary.errorCode()).isEqualTo("GUIDE_GRAPH_FAILED");
        assertThat(failingRepo.assistantMessages()).extracting(ConversationMessage::status)
                .contains("FAILED");
    }

    private static final class StubRepo implements AgentConversationRepository {

        private final Set<String> conversations = new HashSet<>();
        private final Map<String, List<ConversationMessage>> messages = new HashMap<>();

        @Override
        public boolean existsConversation(String userId, String conversationId) {
            return conversations.contains(key(userId, conversationId));
        }

        @Override
        public Long initConversation(String userId, String conversationId) {
            conversations.add(key(userId, conversationId));
            return (long) conversations.size();
        }

        @Override
        public List<ConversationMessage> loadRecentMessages(String userId, String conversationId, int limit) {
            List<ConversationMessage> conversationMessages =
                    messages.getOrDefault(key(userId, conversationId), List.of());
            return conversationMessages.stream().limit(limit).toList();
        }

        @Override
        public ConversationMessage saveUserMessage(
                String userId,
                String conversationId,
                String turnId,
                String correlationId,
                String content
        ) {
            return saveMessage(userId, conversationId, turnId, correlationId, "user", content, "SUCCEEDED");
        }

        @Override
        public ConversationMessage saveAssistantMessage(
                String userId,
                String conversationId,
                String turnId,
                String correlationId,
                String content,
                String status
        ) {
            return saveMessage(userId, conversationId, turnId, correlationId, "assistant", content, status);
        }

        private ConversationMessage saveMessage(
                String userId,
                String conversationId,
                String turnId,
                String correlationId,
                String role,
                String content,
                String status
        ) {
            initConversation(userId, conversationId);
            String key = key(userId, conversationId);
            List<ConversationMessage> conversationMessages =
                    messages.computeIfAbsent(key, _ -> new ArrayList<>());
            ConversationMessage message = new ConversationMessage(
                    (long) conversationMessages.size() + 1,
                    turnId + ":" + role + ":" + UUID.randomUUID(),
                    1L,
                    role,
                    content,
                    status,
                    correlationId,
                    conversationMessages.size() + 1,
                    OffsetDateTime.now()
            );
            conversationMessages.add(message);
            return message;
        }

        @Override
        public void createOrUpdateTurn(
                String userId,
                String conversationId,
                String turnId,
                String requestId,
                String status,
                String intent,
                String targetWorkflow
        ) {
        }

        List<ConversationMessage> assistantMessages() {
            return messages.values().stream()
                    .flatMap(List::stream)
                    .filter(message -> "assistant".equals(message.role()))
                    .toList();
        }

        private String key(String userId, String conversationId) {
            return userId + "\n" + conversationId;
        }
    }

    private static final class TimeoutMainIntentRouterService extends MainIntentRouterService {
        TimeoutMainIntentRouterService() {
            super(null, null, null);
        }

        @Override
        public MainIntentDecision route(String userMessage, String conversationMemory) {
            throw new RuntimeException(new SocketTimeoutException("intent service timeout"));
        }
    }

    private static final class StubSlotFilling extends CartManageSlotFillingService {
        private final CartManageSlots slots;

        StubSlotFilling(CartManageSlots slots) {
            super(null);
            this.slots = slots;
        }

        @Override
        public CartManageSlots extract(String userMessage, String conversationMemory) {
            return slots;
        }
    }

    private static final class StubCartCommand implements CartCommandService {
        @Override
        public CartMutationResult addItem(
                String userId,
                String conversationId,
                String productId,
                String skuId,
                int quantity,
                BigDecimal expectedUnitPrice
        ) {
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult removeItem(String userId, String conversationId, String cartItemId) {
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult updateQuantity(
                String userId, String conversationId, String cartItemId, int quantity
        ) {
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult clearCart(String userId, String conversationId) {
            return CartMutationResult.ok(null);
        }
    }
}
