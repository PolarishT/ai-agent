package com.bytedance.ai.graph.orchestration;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartState;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.catalog.api.CatalogInventoryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.cartmanage.application.CartCommandService;
import com.bytedance.ai.graph.cartmanage.CartMutationResult;
import com.bytedance.ai.graph.api.GuideGraphIntent;
import com.bytedance.ai.graph.api.GuideNodeExecutionResult;
import com.bytedance.ai.graph.api.GuideNodeResult;
import com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys;
import com.bytedance.ai.graph.cartmanage.subgraph.CartWorkflowStatus;
import com.bytedance.ai.graph.conversation.persistence.AgentConversationRepository;
import com.bytedance.ai.graph.conversation.persistence.jdbc.JdbcAgentConversationRepository;
import com.bytedance.ai.graph.conversation.context.ConversationContextItemStatus;
import com.bytedance.ai.graph.conversation.context.ConversationContextManager;
import com.bytedance.ai.graph.conversation.context.ConversationRuntimeContext;
import com.bytedance.ai.graph.ordermanage.AddressSnapshot;
import com.bytedance.ai.graph.ordermanage.persistence.MockOrderRecord;
import com.bytedance.ai.graph.ordermanage.persistence.MockOrderRepository;
import com.bytedance.ai.graph.ordermanage.application.OrderCartSnapshotService;
import com.bytedance.ai.graph.ordermanage.application.OrderCommandService;
import com.bytedance.ai.graph.ordermanage.OrderManageStateKeys;
import com.bytedance.ai.graph.ordermanage.OrderManageStatus;
import com.bytedance.ai.graph.ordermanage.OrderManageSubgraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class GuideStateGraphFactoryTests {

    private GuideStateGraphFactory factory;
    private AgentConversationRepository conversationRepository;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:guidegraph-" + java.util.UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "sa",
                ""
        ));
        createSchema(jdbc);
        conversationRepository = new JdbcAgentConversationRepository(jdbc);
        factory = new GuideStateGraphFactory(conversationRepository);
    }

    @Test
    void compilesGuideStateGraph() {
        CompiledGraph graph = factory.compile(event -> {
        });

        assertThat(graph).isNotNull();
    }

    @Test
    void defaultsMockRouterToClarifyWorkflow() throws Exception {
        OverAllState state = invoke(Map.of());

        assertThat(GuideGraphStateValues.intent(state, GuideGraphStateKeys.INTENT)).contains(GuideGraphIntent.CLARIFY);
        assertThat(state.value(GuideGraphStateKeys.TARGET_WORKFLOW, ""))
                .isEqualTo(GuideGraphNodeNames.CLARIFY_WORKFLOW);
        assertTraceOrder(state, GuideGraphNodeNames.INIT_CONVERSATION, GuideGraphNodeNames.CLARIFY_WORKFLOW);
    }

    @Test
    void firstRequestInitializesConversationThenSavesUserMessage() throws Exception {
        OverAllState state = invoke(Map.of());

        assertThat(state.value(GuideGraphStateKeys.CONVERSATION_EXISTS, true)).isFalse();
        assertTraceOrder(state, GuideGraphNodeNames.INIT_CONVERSATION, GuideGraphNodeNames.CLARIFY_WORKFLOW);
    }

    @Test
    void secondRequestLoadsConversationContextThenSavesUserMessage() throws Exception {
        invoke(Map.of(), "conversation-repeat", "run-first");

        OverAllState state = invoke(Map.of(), "conversation-repeat", "run-second");

        assertThat(state.value(GuideGraphStateKeys.CONVERSATION_EXISTS, false)).isTrue();
        assertThat(state.value(GuideGraphStateKeys.MESSAGE_COUNT, 0)).isGreaterThanOrEqualTo(1);
        assertTraceOrder(state, GuideGraphNodeNames.LOAD_CONVERSATION_CONTEXT, GuideGraphNodeNames.CLARIFY_WORKFLOW);
    }

    @ParameterizedTest
    @EnumSource(GuideGraphIntent.class)
    void routesEveryIntentToExpectedWorkflow(GuideGraphIntent intent) throws Exception {
        OverAllState state = invoke(Map.of(GuideGraphStateKeys.INITIAL_INTENT, intent));

        String expectedWorkflow = GuideGraphWorkflows.targetFor(intent);
        assertThat(GuideGraphStateValues.intent(state, GuideGraphStateKeys.INTENT)).contains(intent);
        assertThat(state.value(GuideGraphStateKeys.TARGET_WORKFLOW, ""))
                .isEqualTo(expectedWorkflow);
        assertTraceOrder(state, GuideGraphNodeNames.INIT_CONVERSATION, expectedWorkflow);
        assertThat(state.<GuideNodeResult>value(GuideGraphStateKeys.LAST_NODE_RESULT))
                .map(GuideNodeResult::nodeName)
                .contains(GuideGraphNodeNames.BUILD_ANSWER_CONTEXT);
    }

    @Test
    void productSearchStillRoutesToSearchWorkflow() throws Exception {
        OverAllState state = invoke(Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.PRODUCT_SEARCH));

        assertThat(GuideGraphStateValues.intent(state, GuideGraphStateKeys.INTENT))
                .contains(GuideGraphIntent.PRODUCT_SEARCH);
        assertThat(state.value(GuideGraphStateKeys.TARGET_WORKFLOW, ""))
                .isEqualTo(GuideGraphNodeNames.PRODUCT_QUERY_WORKFLOW);
        assertTraceOrder(state, GuideGraphNodeNames.INIT_CONVERSATION, GuideGraphNodeNames.PRODUCT_QUERY_WORKFLOW);
    }

    @Test
    void answerContextIsWrittenOnlyAsSeparateStateAndDoesNotContainTrace() throws Exception {
        OverAllState state = invoke(Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.PRODUCT_SEARCH));

        Map<String, Object> answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT, Map.of());
        assertThat(answerContext).containsEntry("targetWorkflow", GuideGraphNodeNames.PRODUCT_QUERY_WORKFLOW);
        assertThat(answerContext)
                .doesNotContainKeys(GuideGraphStateKeys.NODE_RESULTS, GuideGraphStateKeys.LAST_NODE_RESULT);
    }

    @Test
    void buildAnswerContextUsesFailureCopyWhenErrorCodeExistsWithoutNodeMessage() throws Exception {
        OverAllState state = invokeWithOverrides(
                Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.PRODUCT_SEARCH),
                Map.of(GuideGraphNodeNames.PRODUCT_QUERY_WORKFLOW,
                        _ -> GuideNodeExecutionResult.withStateUpdates(
                                Map.of(GuideGraphStateKeys.ERROR_CODE, "UPSTREAM_FAILED"),
                                Map.of("forcedError", true)
                        ))
        );

        Map<String, Object> answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT, Map.of());
        assertThat(answerContext.get("answer"))
                .isEqualTo("当前请求处理失败，请稍后重试，或换一种说法重新发送。");
        assertThat(String.valueOf(answerContext.get("answer"))).doesNotContain("我已经处理完成");
    }

    @Test
    void clarifyWorkflowWritesUserVisibleMessageAndNeedUserInput() throws Exception {
        OverAllState state = invoke(Map.of());

        assertThat(state.value(GuideGraphStateKeys.NODE_MESSAGE, ""))
                .contains("我暂时没能识别你的操作");
        assertThat(state.value(GuideGraphStateKeys.NEED_USER_INPUT, false)).isTrue();
        assertThat(state.value(GuideGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo("WAITING_CLARIFICATION");
    }

    @Test
    void cartViewSuccessStillReturnsCartListMessage() throws Exception {
        String cartList = "你的购物车中有：\n1. 轻量通勤双肩包 x 1";
        OverAllState state = invokeWithOverrides(
                Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.CART_MANAGE),
                Map.of(GuideGraphNodeNames.CART_MANAGE_WORKFLOW,
                        _ -> GuideNodeExecutionResult.withStateUpdates(
                                Map.of(
                                        CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.VIEW_SUCCESS.name(),
                                        CartGraphStateKeys.NODE_MESSAGE, cartList
                                ),
                                Map.of("cartView", true)
                        ))
        );

        Map<String, Object> answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT, Map.of());
        assertThat(answerContext.get("answer")).isEqualTo(cartList);
    }

    @Test
    void cartAddSuccessStillReturnsAddSuccessMessage() throws Exception {
        String addSuccess = "已将「轻量通勤双肩包」加入购物车，数量 1。";
        OverAllState state = invokeWithOverrides(
                Map.of(GuideGraphStateKeys.INITIAL_INTENT, GuideGraphIntent.ADD_TO_CART),
                Map.of(GuideGraphNodeNames.CART_MANAGE_WORKFLOW,
                        _ -> GuideNodeExecutionResult.withStateUpdates(
                                Map.of(
                                        CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.ADD_SUCCESS.name(),
                                        CartGraphStateKeys.NODE_MESSAGE, addSuccess
                                ),
                                Map.of("cartAdd", true)
                        ))
        );

        Map<String, Object> answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT, Map.of());
        assertThat(answerContext.get("answer")).isEqualTo(addSuccess);
    }

    @Test
    void createOrderUsesOrderManageSubgraphWhenFactoryProvided() throws Exception {
        OrderGraphRig rig = new OrderGraphRig(cart(item(1L, 101L, "苹果", 2)));
        GuideStateGraphFactory orderFactory = new GuideStateGraphFactory(
                conversationRepository,
                null,
                null,
                null,
                rig.factory,
                null,
                rig.pending
        );
        CompiledGraph graph = orderFactory.compile(event -> {
        });
        Map<String, Object> initialState = new java.util.LinkedHashMap<>();
        initialState.put(GuideGraphStateKeys.USER_ID, "user-1");
        initialState.put(GuideGraphStateKeys.CONVERSATION_ID, "conversation-order");
        initialState.put(GuideGraphStateKeys.MESSAGE, "结算购物车");
        initialState.put(GuideGraphStateKeys.RUN_ID, "run-order");
        initialState.put(GuideGraphStateKeys.CORRELATION_ID, "corr-order");

        OverAllState state = graph.invoke(initialState).orElseThrow();

        assertThat(state.value(GuideGraphStateKeys.MAIN_INTENT, ""))
                .isEqualTo("CREATE_ORDER");
        assertThat(state.value(GuideGraphStateKeys.TARGET_WORKFLOW, ""))
                .isEqualTo(GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW);
        assertThat(state.value(GuideGraphStateKeys.WRITE_ACTION, false)).isTrue();
        assertThat(state.value(GuideGraphStateKeys.ROUTE_SOURCE, ""))
                .isEqualTo("RULE");
        assertThat(state.value(GuideGraphStateKeys.ORDER_WORKFLOW_DISPATCHED, false)).isTrue();
        List<GuideNodeResult> nodeResults = state.value(GuideGraphStateKeys.NODE_RESULTS, List.<GuideNodeResult>of());
        assertThat(nodeResults).extracting(GuideNodeResult::nodeName)
                .containsSubsequence(
                        GuideGraphNodeNames.MAIN_INTENT_ROUTER,
                        GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW,
                        GuideGraphNodeNames.BUILD_ANSWER_CONTEXT
                );
        GuideNodeResult routerResult = nodeResults.stream()
                .filter(result -> GuideGraphNodeNames.MAIN_INTENT_ROUTER.equals(result.nodeName()))
                .findFirst()
                .orElseThrow();
        assertThat(routerResult.metadata())
                .containsEntry(GuideGraphStateKeys.ROUTE_SOURCE, "RULE");
        assertThat(rig.pending.active).isPresent();
        assertThat(rig.pending.active.get().status()).isEqualTo(OrderManageStatus.WAITING_ADDRESS);
        assertThat(state.value(OrderManageStateKeys.NODE_MESSAGE, ""))
                .contains("请补充")
                .doesNotContain("Workflow is not implemented");
        Map<String, Object> answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT, Map.of());
        assertThat(answerContext.get("answer"))
                .asString()
                .contains("请补充")
                .doesNotContain("Workflow is not implemented");
    }

    @Test
    void createOrderFailsWhenOrderWorkflowIsNotDispatched() throws Exception {
        OverAllState state = invokeWithMessage(Map.of(), "结算购物车");

        assertThat(state.value(GuideGraphStateKeys.TARGET_WORKFLOW, ""))
                .isEqualTo(GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW);
        assertThat(state.value(GuideGraphStateKeys.ROUTE_SOURCE, ""))
                .isEqualTo("RULE");
        assertThat(state.value(GuideGraphStateKeys.ERROR_CODE, ""))
                .isEqualTo("ORDER_WORKFLOW_NOT_DISPATCHED");
        assertThat(state.value(GuideGraphStateKeys.ERROR_MESSAGE, ""))
                .isEqualTo("下单流程没有被正确执行，请检查主图 workflow 路由。");
        Map<String, Object> answerContext = state.value(GuideGraphStateKeys.ANSWER_CONTEXT, Map.of());
        assertThat(answerContext.get("answer"))
                .isEqualTo("下单流程没有被正确执行，请检查主图 workflow 路由。");
        List<GuideNodeResult> nodeResults = state.value(GuideGraphStateKeys.NODE_RESULTS, List.<GuideNodeResult>of());
        assertThat(nodeResults).last()
                .extracting(GuideNodeResult::status)
                .isEqualTo(com.bytedance.ai.graph.api.NodeRunStatus.FAILED);
    }

    private OverAllState invoke(Map<String, Object> extraState) throws Exception {
        return invoke(extraState, "conversation-1", "run-1");
    }

    private OverAllState invoke(Map<String, Object> extraState, String conversationId, String runId) throws Exception {
        return invokeWithOverrides(extraState, conversationId, runId, Map.of());
    }

    private OverAllState invokeWithOverrides(
            Map<String, Object> extraState,
            Map<String, GuideGraphNodeAction> actionOverrides
    ) throws Exception {
        return invokeWithOverrides(extraState, "conversation-1", "run-1", actionOverrides);
    }

    private OverAllState invokeWithMessage(Map<String, Object> extraState, String message) throws Exception {
        return invokeWithOverrides(extraState, "conversation-1", "run-1", message, Map.of());
    }

    private OverAllState invokeWithOverrides(
            Map<String, Object> extraState,
            String conversationId,
            String runId,
            Map<String, GuideGraphNodeAction> actionOverrides
    ) throws Exception {
        return invokeWithOverrides(extraState, conversationId, runId, "推荐 300 元以下的双肩包", actionOverrides);
    }

    private OverAllState invokeWithOverrides(
            Map<String, Object> extraState,
            String conversationId,
            String runId,
            String message,
            Map<String, GuideGraphNodeAction> actionOverrides
    ) throws Exception {
        CompiledGraph graph = factory.compile(event -> {
        }, actionOverrides);
        Map<String, Object> initialState = new java.util.LinkedHashMap<>();
        initialState.put(GuideGraphStateKeys.USER_ID, "user-1");
        initialState.put(GuideGraphStateKeys.CONVERSATION_ID, conversationId);
        initialState.put(GuideGraphStateKeys.MESSAGE, message);
        initialState.put(GuideGraphStateKeys.RUN_ID, runId);
        initialState.put(GuideGraphStateKeys.CORRELATION_ID, "corr-1");
        extraState.forEach((key, value) -> {
            if (value instanceof GuideGraphIntent intent) {
                initialState.put(key, intent.name());
            } else {
                initialState.put(key, value);
            }
        });
        return graph.invoke(initialState).orElseThrow();
    }

    private void assertTraceOrder(OverAllState state, String conversationBranchNode, String workflowNode) {
        List<GuideNodeResult> nodeResults = state.value(GuideGraphStateKeys.NODE_RESULTS, List.<GuideNodeResult>of());
        List<String> expectedNodes = conversationBranchNode.equals(GuideGraphNodeNames.LOAD_CONVERSATION_CONTEXT)
                ? List.of(
                        GuideGraphNodeNames.CHECK_CONVERSATION,
                        GuideGraphNodeNames.LOAD_CONVERSATION_CONTEXT,
                        GuideGraphNodeNames.SAVE_USER_MESSAGE,
                        GuideGraphNodeNames.MAIN_INTENT_ROUTER,
                        workflowNode,
                        GuideGraphNodeNames.BUILD_ANSWER_CONTEXT
                )
                : List.of(
                        GuideGraphNodeNames.CHECK_CONVERSATION,
                        conversationBranchNode,
                        GuideGraphNodeNames.LOAD_CONVERSATION_CONTEXT,
                        GuideGraphNodeNames.SAVE_USER_MESSAGE,
                        GuideGraphNodeNames.MAIN_INTENT_ROUTER,
                        workflowNode,
                        GuideGraphNodeNames.BUILD_ANSWER_CONTEXT
                );
        assertThat(nodeResults).extracting(GuideNodeResult::nodeName)
                .containsExactlyElementsOf(expectedNodes);
        assertThat(nodeResults).extracting(GuideNodeResult::nodeName)
                .doesNotHaveDuplicates();
    }

    private void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE agent_conversations (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    conversation_id VARCHAR(128) NOT NULL UNIQUE,
                    user_id VARCHAR(128) NOT NULL,
                    title VARCHAR(200),
                    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    message_count INTEGER NOT NULL DEFAULT 0,
                    metadata CLOB NOT NULL DEFAULT '{}',
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_message_at TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_conversation_messages (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    message_id VARCHAR(128) NOT NULL UNIQUE,
                    conversation_id BIGINT NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    content CLOB NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'SUCCEEDED',
                    token_count INTEGER,
                    correlation_id VARCHAR(128),
                    sequence_no INTEGER NOT NULL,
                    metadata CLOB NOT NULL DEFAULT '{}',
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (conversation_id, sequence_no)
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_turn (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    turn_id VARCHAR(64) NOT NULL UNIQUE,
                    request_id VARCHAR(64),
                    conversation_id VARCHAR(64) NOT NULL,
                    user_id VARCHAR(64) NOT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    intent VARCHAR(64),
                    target_workflow VARCHAR(64),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    completed_at TIMESTAMP
                )
                """);
    }

    private static CartItemView item(Long itemId, Long spuId, String title, int quantity) {
        BigDecimal unitPrice = new BigDecimal("9.90");
        return new CartItemView(itemId, spuId, "SPU-" + spuId, title, "brand", null, quantity,
                unitPrice, unitPrice.multiply(BigDecimal.valueOf(quantity)), 10);
    }

    private static CartView cart(CartItemView... items) {
        List<CartItemView> itemList = List.of(items);
        BigDecimal amount = itemList.stream()
                .map(CartItemView::lineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int itemCount = itemList.stream().mapToInt(item -> item.quantity() == null ? 0 : item.quantity()).sum();
        return new CartView("cart-1", "user-1", "conversation-order", CartState.IN_CART, "CNY",
                amount, itemCount, Map.of(), itemList);
    }

    private static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }

    private static final class OrderGraphRig {
        CartView cart;
        final StubContextManager pending = new StubContextManager();
        final StubCatalog catalog = new StubCatalog();
        final StubInventory inventory = new StubInventory();
        final StubCartCommand cartCommand = new StubCartCommand();
        final StubMockOrders mockOrders = new StubMockOrders();
        final OrderCartSnapshotService snapshots = new OrderCartSnapshotService();
        final OrderManageSubgraphFactory factory;

        OrderGraphRig(CartView cart) {
            this.cart = cart;
            OrderCommandService commandService = new OrderCommandService(
                    pending,
                    (userId, conversationId) -> this.cart,
                    catalog,
                    inventory,
                    cartCommand,
                    mockOrders,
                    snapshots
            );
            this.factory = new OrderManageSubgraphFactory(
                    (userId, conversationId) -> this.cart,
                    catalog,
                    pending,
                    new com.bytedance.ai.graph.ordermanage.application.OrderAddressResolver(),
                    snapshots,
                    commandService
            );
        }
    }

    private record TestPending(
            Long id,
            Map<String, Object> cartSnapshot,
            String cartSnapshotHash,
            Map<String, Object> addressSnapshot,
            BigDecimal amountSnapshot,
            OrderManageStatus status,
            String failReason,
            String orderNo,
            LocalDateTime expireAt
    ) {
    }

    private static final class StubContextManager implements ConversationContextManager {
        Optional<TestPending> active = Optional.empty();
        long nextId = 1L;

        @Override
        public ConversationRuntimeContext load(String userId, String conversationId) {
            ConversationRuntimeContext.OrderContext order = active.map(this::toOrderContext).orElse(null);
            return new ConversationRuntimeContext(
                    1L, userId, conversationId, List.of(), null, List.of(), null,
                    order, null, null, Map.of(), Map.of(), List.of());
        }

        @Override
        public void saveProductCandidates(String userId, String conversationId, String sourceTurnId,
                                          String sourceWorkflow,
                                          List<ConversationRuntimeContext.ProductCandidateItem> candidates,
                                          LocalDateTime expiresAt) {
        }

        @Override
        public void saveTaskChain(String userId, String conversationId, String sourceTurnId,
                                  String sourceWorkflow,
                                  ConversationRuntimeContext.TaskChain taskChain,
                                  LocalDateTime expiresAt) {
        }

        @Override
        public ConversationRuntimeContext.TaskChain loadTaskChain(String userId, String conversationId,
                                                                  String taskChainId) {
            return null;
        }

        @Override
        public boolean markChainStep(String userId, String conversationId, String taskChainId, int stepNo,
                                     String newStepStatus, ConversationRuntimeContext.StepOutput output,
                                     String turnId) {
            return false;
        }

        @Override
        public boolean transitionChainStatus(String userId, String conversationId, String taskChainId,
                                             String newChainStatus, String turnId) {
            return false;
        }

        @Override
        public void updateFocus(String userId, String conversationId, String sourceTurnId, String sourceWorkflow,
                                ConversationRuntimeContext.Focus focus, LocalDateTime expiresAt) {
        }

        @Override
        public ConversationRuntimeContext.PendingClarification savePendingClarification(
                String userId,
                String conversationId,
                String sourceTurnId,
                String sourceWorkflow,
                ConversationRuntimeContext.PendingClarification clarification,
                LocalDateTime expiresAt
        ) {
            return clarification;
        }

        @Override
        public void consumePendingClarification(Long contextItemId) {
        }

        @Override
        public void updateCartSnapshot(String userId, String conversationId, String sourceTurnId, String sourceWorkflow,
                                       ConversationRuntimeContext.CartSnapshot cartSnapshot, LocalDateTime expiresAt) {
        }

        @Override
        public ConversationRuntimeContext.OrderContext updateOrderContext(
                String userId,
                String conversationId,
                String sourceTurnId,
                String sourceWorkflow,
                ConversationRuntimeContext.OrderContext orderContext,
                LocalDateTime expiresAt
        ) {
            TestPending saved = new TestPending(nextId++, orderContext.cartSnapshot(), orderContext.cartSnapshotHash(),
                    orderContext.addressSnapshot(), orderContext.amountSnapshot(),
                    OrderManageStatus.valueOf(orderContext.orderStatus()), orderContext.failReason(),
                    orderContext.orderNo(), expiresAt);
            active = Optional.of(saved);
            return toOrderContext(saved);
        }

        @Override
        public void updateLastTurn(String userId, String conversationId, String sourceTurnId, String sourceWorkflow,
                                   ConversationRuntimeContext.LastTurn lastTurn, LocalDateTime expiresAt) {
        }

        @Override
        public boolean transitionOrderContextStatus(Long contextItemId, String expectedOrderStatus,
                                                    ConversationRuntimeContext.OrderContext orderContext,
                                                    ConversationContextItemStatus itemStatus) {
            if (active.isEmpty()
                    || !active.get().id().equals(contextItemId)
                    || !active.get().status().name().equals(expectedOrderStatus)) {
                return false;
            }
            if (itemStatus == ConversationContextItemStatus.COMPLETED
                    || itemStatus == ConversationContextItemStatus.FAILED
                    || itemStatus == ConversationContextItemStatus.CANCELLED
                    || itemStatus == ConversationContextItemStatus.EXPIRED) {
                active = Optional.empty();
            } else {
                active = Optional.of(new TestPending(contextItemId, orderContext.cartSnapshot(),
                        orderContext.cartSnapshotHash(), orderContext.addressSnapshot(), orderContext.amountSnapshot(),
                        OrderManageStatus.valueOf(orderContext.orderStatus()), orderContext.failReason(),
                        orderContext.orderNo(), orderContext.expiresAt()));
            }
            return true;
        }

        @Override
        public void markContextItemStatus(Long contextItemId, ConversationContextItemStatus status) {
            active = Optional.empty();
        }

        private ConversationRuntimeContext.OrderContext toOrderContext(TestPending pending) {
            return new ConversationRuntimeContext.OrderContext(pending.id(), pending.cartSnapshot(),
                    pending.cartSnapshotHash(), pending.addressSnapshot(), pending.amountSnapshot(),
                    pending.status().name(), pending.failReason(), pending.orderNo(), pending.expireAt(), Map.of());
        }
    }

    private static final class StubCatalog implements CatalogQueryFacade {
        @Override
        public CatalogProductView getProduct(Long productId) {
            return new CatalogProductView(productId, "苹果", "brand", "fruit", null,
                    new BigDecimal("9.90"), new BigDecimal("9.90"), new BigDecimal("9.90"), 10, "", "ACTIVE",
                    Map.of(), Map.of(), List.of(), OffsetDateTime.now(), OffsetDateTime.now());
        }

        @Override
        public List<CatalogSkuView> listSkus(Long productId) {
            return List.of();
        }
    }

    private static final class StubInventory implements CatalogInventoryFacade {
        @Override
        public void decreaseStock(Long spuId, int quantity) {
        }
    }

    private static final class StubCartCommand implements CartCommandService {
        @Override
        public CartMutationResult addItem(String userId, String conversationId, String productId, String skuId,
                                          int quantity, BigDecimal expectedUnitPrice) {
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult removeItem(String userId, String conversationId, String cartItemId) {
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult updateQuantity(String userId, String conversationId, String cartItemId, int quantity) {
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult clearCart(String userId, String conversationId) {
            return CartMutationResult.ok(null);
        }
    }

    private static final class StubMockOrders implements MockOrderRepository {
        @Override
        public MockOrderRecord create(String orderNo, String userId, String conversationId, Map<String, Object> items,
                                      Map<String, Object> address, BigDecimal totalAmount) {
            return new MockOrderRecord(1L, orderNo, userId, conversationId, items, address, totalAmount,
                    "CREATED", LocalDateTime.now());
        }
    }
}
