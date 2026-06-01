package com.bytedance.ai.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.bytedance.ai.graph.api.AgentStreamEvent;
import com.bytedance.ai.graph.api.GuideGraphIntent;
import com.bytedance.ai.graph.api.GuideNodeExecutionResult;
import com.bytedance.ai.graph.api.GuideNodeResult;
import com.bytedance.ai.graph.api.NodeRunStatus;
import com.bytedance.ai.graph.cartmanage.application.CartManageWorkflowNode;
import com.bytedance.ai.graph.cartmanage.CartManageWorkflowResult;
import com.bytedance.ai.graph.cartmanage.subgraph.CartManageSubgraphFactory;
import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import com.bytedance.ai.graph.conversation.persistence.AgentConversationRepository;
import com.bytedance.ai.graph.conversation.ConversationMessage;
import com.bytedance.ai.graph.intent.MainIntent;
import com.bytedance.ai.graph.intent.MainIntentDecision;
import com.bytedance.ai.graph.intent.service.MainIntentRouterService;
import com.bytedance.ai.graph.ordermanage.OrderManageSubgraphFactory;
import com.bytedance.ai.graph.ordermanage.OrderManageStateKeys;
import com.bytedance.ai.graph.ordermanage.OrderManageStatus;
import com.bytedance.ai.graph.ordermanage.persistence.PendingOrderActionRepository;
import com.bytedance.ai.graph.product.query.ProductQueryGraphStateKeys;
import com.bytedance.ai.graph.product.query.subgraph.ProductQuerySubgraphFactory;
import com.bytedance.ai.shared.support.RagLogFields;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@Component
public class GuideStateGraphFactory {

    private static final Logger log = LoggerFactory.getLogger(GuideStateGraphFactory.class);

    public static final String GRAPH_NAME = "ecommerce-guide-state-graph-v1";
    private static final String MAIN_INTENT_LLM_TIMEOUT = "MAIN_INTENT_LLM_TIMEOUT";
    private static final String MAIN_INTENT_ROUTER_FAILED = "MAIN_INTENT_ROUTER_FAILED";
    private static final String ORDER_WORKFLOW_NOT_DISPATCHED = "ORDER_WORKFLOW_NOT_DISPATCHED";
    private static final String ORDER_WORKFLOW_NOT_DISPATCHED_MESSAGE =
            "下单流程没有被正确执行，请检查主图 workflow 路由。";
    private static final String CLARIFY_MESSAGE =
            "我暂时没能识别你的操作，请换一种说法，例如‘查看购物车’、‘添加商品到购物车’或‘结算购物车’。";
    private static final String FAILURE_MESSAGE =
            "当前请求处理失败，请稍后重试，或换一种说法重新发送。";
    private static final String NO_BUSINESS_RESULT_MESSAGE =
            "当前没有可展示的业务结果，请换一种说法重新发送。";

    private final AgentConversationRepository conversationRepository;
    private final MainIntentRouterService mainIntentRouterService;
    private final CartManageWorkflowNode cartManageWorkflowNode;
    private final CartManageSubgraphFactory cartManageSubgraphFactory;
    private final OrderManageSubgraphFactory orderManageSubgraphFactory;
    private final PendingOrderActionRepository pendingOrderActionRepository;
    private final ProductQuerySubgraphFactory productQuerySubgraphFactory;

    public GuideStateGraphFactory(AgentConversationRepository conversationRepository) {
        this(conversationRepository, (MainIntentRouterService) null, (CartManageWorkflowNode) null, null, null, null, null);
    }

    public GuideStateGraphFactory(
            AgentConversationRepository conversationRepository,
            CartManageWorkflowNode cartManageWorkflowNode
    ) {
        this(conversationRepository, (MainIntentRouterService) null, cartManageWorkflowNode, null, null, null, null);
    }

    @Autowired
    public GuideStateGraphFactory(
            AgentConversationRepository conversationRepository,
            ObjectProvider<MainIntentRouterService> mainIntentRouterServiceProvider,
            ObjectProvider<CartManageWorkflowNode> cartManageWorkflowNodeProvider,
            ObjectProvider<CartManageSubgraphFactory> cartManageSubgraphFactoryProvider,
            OrderManageSubgraphFactory orderManageSubgraphFactory,
            ObjectProvider<PendingOrderActionRepository> pendingOrderActionRepositoryProvider,
            ObjectProvider<ProductQuerySubgraphFactory> productQuerySubgraphFactoryProvider
    ) {
        this(
                conversationRepository,
                mainIntentRouterServiceProvider.getIfAvailable(),
                cartManageWorkflowNodeProvider.getIfAvailable(),
                cartManageSubgraphFactoryProvider.getIfAvailable(),
                orderManageSubgraphFactory,
                pendingOrderActionRepositoryProvider.getIfAvailable(),
                productQuerySubgraphFactoryProvider.getIfAvailable()
        );
    }

    GuideStateGraphFactory(
            AgentConversationRepository conversationRepository,
            MainIntentRouterService mainIntentRouterService,
            CartManageWorkflowNode cartManageWorkflowNode,
            CartManageSubgraphFactory cartManageSubgraphFactory,
            OrderManageSubgraphFactory orderManageSubgraphFactory,
            PendingOrderActionRepository pendingOrderActionRepository,
            ProductQuerySubgraphFactory productQuerySubgraphFactory
    ) {
        this.conversationRepository = conversationRepository;
        this.mainIntentRouterService = mainIntentRouterService;
        this.cartManageWorkflowNode = cartManageWorkflowNode;
        this.cartManageSubgraphFactory = cartManageSubgraphFactory;
        this.orderManageSubgraphFactory = orderManageSubgraphFactory;
        this.pendingOrderActionRepository = pendingOrderActionRepository;
        this.productQuerySubgraphFactory = productQuerySubgraphFactory;
    }

    public CompiledGraph compile(Consumer<AgentStreamEvent> eventSink) {
        return compile(eventSink, Map.of());
    }

    CompiledGraph compile(Consumer<AgentStreamEvent> eventSink, Map<String, GuideGraphNodeAction> actionOverrides) {
        try {
            GuideGraphNodeTemplate nodeTemplate = new GuideGraphNodeTemplate(eventSink);
            StateGraph graph = new StateGraph(GRAPH_NAME, keyStrategyFactory());
            graph.addNode(GuideGraphNodeNames.CHECK_CONVERSATION,
                    AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                            GuideGraphNodeNames.CHECK_CONVERSATION, state,
                            actionOverrides.getOrDefault(
                                    GuideGraphNodeNames.CHECK_CONVERSATION, this::checkConversation))));
            graph.addNode(GuideGraphNodeNames.LOAD_MEMORY,
                    AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                            GuideGraphNodeNames.LOAD_MEMORY, state,
                            actionOverrides.getOrDefault(GuideGraphNodeNames.LOAD_MEMORY, this::loadMemory))));
            graph.addNode(GuideGraphNodeNames.INIT_CONVERSATION,
                    AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                            GuideGraphNodeNames.INIT_CONVERSATION, state,
                            actionOverrides.getOrDefault(
                                    GuideGraphNodeNames.INIT_CONVERSATION, this::initConversation))));
            graph.addNode(GuideGraphNodeNames.SAVE_USER_MESSAGE,
                    AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                            GuideGraphNodeNames.SAVE_USER_MESSAGE, state,
                            actionOverrides.getOrDefault(GuideGraphNodeNames.SAVE_USER_MESSAGE, this::saveUserMessage))));
            graph.addNode(GuideGraphNodeNames.MAIN_INTENT_ROUTER,
                    AsyncNodeAction.node_async(state -> {
                        Map<String, Object> result = nodeTemplate.execute(
                                GuideGraphNodeNames.MAIN_INTENT_ROUTER, state,
                                actionOverrides.getOrDefault(GuideGraphNodeNames.MAIN_INTENT_ROUTER,
                                        this::mainIntentRouter));
                        emitWorkflowStarted(eventSink, state, result);
                        return result;
                    }));
            Map<String, GuideGraphNodeAction> defaultWorkflowActions = defaultWorkflowActions();
            boolean cartSubgraphWired = cartManageSubgraphFactory != null
                    && !actionOverrides.containsKey(GuideGraphNodeNames.CART_MANAGE_WORKFLOW);
            boolean orderSubgraphWired = orderManageSubgraphFactory != null
                    && !actionOverrides.containsKey(GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW);
            boolean productQuerySubgraphWired = productQuerySubgraphFactory != null
                    && !actionOverrides.containsKey(GuideGraphNodeNames.PRODUCT_QUERY_WORKFLOW);
            for (String workflowNode : new LinkedHashSet<>(GuideGraphWorkflows.targets().values())) {
                if (cartSubgraphWired && GuideGraphNodeNames.CART_MANAGE_WORKFLOW.equals(workflowNode)) {
                    // CART_MANAGE is implemented as a sub-state-graph rather than a single node
                    // action; the legacy single-node CartManageWorkflowNode is kept around only
                    // for tests that explicitly override this node via `actionOverrides`.
                    graph.addNode(workflowNode, cartManageSubgraphFactory.build());
                    continue;
                }
                if (orderSubgraphWired && GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW.equals(workflowNode)) {
                    graph.addNode(workflowNode, AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                            workflowNode, state, this::orderManageWorkflow)));
                    continue;
                }
                if (productQuerySubgraphWired
                        && GuideGraphNodeNames.PRODUCT_QUERY_WORKFLOW.equals(workflowNode)) {
                    graph.addNode(workflowNode, productQuerySubgraphFactory.build());
                    continue;
                }
                GuideGraphNodeAction defaultAction = defaultWorkflowActions.getOrDefault(
                        workflowNode, workflowNode(workflowNode));
                graph.addNode(workflowNode, AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                        workflowNode, state,
                        actionOverrides.getOrDefault(workflowNode, defaultAction))));
            }
            graph.addNode(GuideGraphNodeNames.BUILD_ANSWER_CONTEXT,
                    AsyncNodeAction.node_async(state -> nodeTemplate.execute(
                            GuideGraphNodeNames.BUILD_ANSWER_CONTEXT, state,
                            actionOverrides.getOrDefault(GuideGraphNodeNames.BUILD_ANSWER_CONTEXT, this::buildAnswerContext))));

            graph.addEdge(StateGraph.START, GuideGraphNodeNames.CHECK_CONVERSATION);
            graph.addConditionalEdges(
                    GuideGraphNodeNames.CHECK_CONVERSATION,
                    AsyncEdgeAction.edge_async(this::routeByConversationExists),
                    conversationExistenceMappings()
            );
            graph.addEdge(GuideGraphNodeNames.LOAD_MEMORY, GuideGraphNodeNames.SAVE_USER_MESSAGE);
            graph.addEdge(GuideGraphNodeNames.INIT_CONVERSATION, GuideGraphNodeNames.SAVE_USER_MESSAGE);
            graph.addEdge(GuideGraphNodeNames.SAVE_USER_MESSAGE, GuideGraphNodeNames.MAIN_INTENT_ROUTER);
            graph.addConditionalEdges(
                    GuideGraphNodeNames.MAIN_INTENT_ROUTER,
                    AsyncEdgeAction.edge_async(this::routeByMockIntent),
                    workflowMappings()
            );
            for (String workflowNode : new LinkedHashSet<>(GuideGraphWorkflows.targets().values())) {
                graph.addEdge(workflowNode, GuideGraphNodeNames.BUILD_ANSWER_CONTEXT);
            }
            graph.addEdge(GuideGraphNodeNames.BUILD_ANSWER_CONTEXT, StateGraph.END);
            return graph.compile();
        } catch (Exception exception) {
            throw new IllegalStateException("Guide StateGraph compile failed", exception);
        }
    }

    private KeyStrategyFactory keyStrategyFactory() {
        return new KeyStrategyFactoryBuilder()
                .defaultStrategy(new ReplaceStrategy())
                .addStrategy(GuideGraphStateKeys.LAST_NODE_RESULT, new ReplaceStrategy())
                .addStrategy(GuideGraphStateKeys.NODE_RESULTS, new ReplaceStrategy())
                .addStrategy(GuideGraphStateKeys.ANSWER_CONTEXT, new ReplaceStrategy())
                .addStrategy(GuideGraphStateKeys.INTENT_SLOTS, new ReplaceStrategy())
                .addStrategy(GuideGraphStateKeys.MISSING_SLOTS, new ReplaceStrategy())
                .build();
    }

    private GuideNodeExecutionResult checkConversation(OverAllState state) {
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        boolean exists = conversationRepository.existsConversation(userId, conversationId);
        return GuideNodeExecutionResult.withStateUpdates(
                Map.of(GuideGraphStateKeys.CONVERSATION_EXISTS, exists),
                Map.of(GuideGraphStateKeys.CONVERSATION_EXISTS, exists)
        );
    }

    private GuideNodeExecutionResult loadMemory(OverAllState state) {
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        java.util.List<ConversationMessage> recentMessages =
                conversationRepository.loadRecentMessages(userId, conversationId, 20);
        return GuideNodeExecutionResult.withStateUpdates(
                Map.of(
                        GuideGraphStateKeys.RECENT_MESSAGES, recentMessages,
                        GuideGraphStateKeys.MESSAGE_COUNT, recentMessages.size()
                ),
                Map.of(GuideGraphStateKeys.MESSAGE_COUNT, recentMessages.size())
        );
    }

    private GuideNodeExecutionResult initConversation(OverAllState state) {
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        Long internalConversationId = conversationRepository.initConversation(userId, conversationId);
        return GuideNodeExecutionResult.withStateUpdates(
                Map.of(GuideGraphStateKeys.CONVERSATION_INTERNAL_ID, internalConversationId),
                Map.of(GuideGraphStateKeys.CONVERSATION_INTERNAL_ID, internalConversationId)
        );
    }

    private GuideNodeExecutionResult saveUserMessage(OverAllState state) {
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        String turnId = requiredString(state, GuideGraphStateKeys.RUN_ID);
        String correlationId = requiredString(state, GuideGraphStateKeys.CORRELATION_ID);
        String content = requiredString(state, GuideGraphStateKeys.MESSAGE);
        ConversationMessage message = conversationRepository.saveUserMessage(
                userId,
                conversationId,
                turnId,
                correlationId,
                content
        );
        conversationRepository.createOrUpdateTurn(
                userId,
                conversationId,
                turnId,
                state.value(GuideGraphStateKeys.REQUEST_ID).map(Object::toString).orElse(null),
                "RUNNING",
                null,
                null
        );
        return GuideNodeExecutionResult.withStateUpdates(
                Map.of("userMessageId", message.messageId()),
                Map.of("userMessageId", message.messageId())
        );
    }

    private GuideNodeExecutionResult mainIntentRouter(OverAllState state) {
        MainIntentRouteResult routeResult = routeMainIntentSafely(state);
        MainIntentDecision decision = routeResult.decision();
        GuideGraphIntent intent = toGuideIntent(decision.intent());
        String targetWorkflow = decision.targetWorkflow();
        conversationRepository.createOrUpdateTurn(
                requiredString(state, GuideGraphStateKeys.USER_ID),
                requiredString(state, GuideGraphStateKeys.CONVERSATION_ID),
                requiredString(state, GuideGraphStateKeys.RUN_ID),
                state.value(GuideGraphStateKeys.REQUEST_ID).map(Object::toString).orElse(null),
                "RUNNING",
                intent.name(),
                targetWorkflow
        );
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(GuideGraphStateKeys.INTENT, intent.name());
        updates.put(GuideGraphStateKeys.MAIN_INTENT, decision.intent().name());
        updates.put(GuideGraphStateKeys.INTENT_CONFIDENCE, decision.confidence());
        updates.put(GuideGraphStateKeys.NEED_CLARIFY, decision.needClarify());
        updates.put(GuideGraphStateKeys.WRITE_ACTION, decision.writeAction());
        updates.put(GuideGraphStateKeys.TARGET_WORKFLOW, targetWorkflow);
        updates.put(GuideGraphStateKeys.INTENT_REASON, decision.reason());
        updates.put(GuideGraphStateKeys.INTENT_SLOTS, decision.slots());
        updates.put(GuideGraphStateKeys.MISSING_SLOTS, decision.missingSlots());
        putIfPresent(updates, GuideGraphStateKeys.ERROR_CODE, routeResult.errorCode());
        putIfPresent(updates, GuideGraphStateKeys.ERROR_MESSAGE, routeResult.errorMessage());
        putIfPresent(updates, GuideGraphStateKeys.NODE_MESSAGE, routeResult.nodeMessage());
        putIfPresent(updates, GuideGraphStateKeys.ROUTE_SOURCE, routeResult.routeSource());
        if (routeResult.llmCalled() != null) {
            updates.put(GuideGraphStateKeys.LLM_CALLED, routeResult.llmCalled());
        }
        boolean failed = routeResult.errorCode() != null;
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "main_intent.graph_state_written")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, failed ? RagLogFields.OUTCOME_FAILURE : RagLogFields.OUTCOME_SUCCESS)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, state.value(GuideGraphStateKeys.CORRELATION_ID, ""))
                .addKeyValue("agent.turn_id", state.value(GuideGraphStateKeys.RUN_ID, ""))
                .addKeyValue("agent.request_id", state.value(GuideGraphStateKeys.REQUEST_ID, ""))
                .addKeyValue("main_intent.intent", decision.intent().name())
                .addKeyValue("main_intent.confidence", decision.confidence())
                .addKeyValue("main_intent.need_clarify", decision.needClarify())
                .addKeyValue("main_intent.write_action", decision.writeAction())
                .addKeyValue("main_intent.target_workflow", targetWorkflow)
                .addKeyValue("main_intent.reason", decision.reason())
                .log("main intent graph state written: turnId={}, requestId={}, intent={}, confidence={}, needClarify={}, writeAction={}, targetWorkflow={}, reason={}",
                        state.value(GuideGraphStateKeys.RUN_ID, ""),
                        state.value(GuideGraphStateKeys.REQUEST_ID, ""),
                        decision.intent(),
                        decision.confidence(),
                        decision.needClarify(),
                        decision.writeAction(),
                        targetWorkflow,
                        decision.reason());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(GuideGraphStateKeys.MAIN_INTENT, decision.intent().name());
        metadata.put(GuideGraphStateKeys.TARGET_WORKFLOW, targetWorkflow);
        metadata.put(GuideGraphStateKeys.WRITE_ACTION, decision.writeAction());
        metadata.put(GuideGraphStateKeys.ROUTE_SOURCE, routeResult.routeSource());
        putIfPresent(metadata, GuideGraphStateKeys.ERROR_CODE, routeResult.errorCode());
        putIfPresent(metadata, GuideGraphStateKeys.ERROR_MESSAGE, routeResult.errorMessage());
        return new GuideNodeExecutionResult(
                failed ? NodeRunStatus.FAILED : null,
                intent,
                null,
                updates,
                metadata
        );
    }

    private MainIntentRouteResult routeMainIntentSafely(OverAllState state) {
        Optional<GuideGraphIntent> initialIntent =
                GuideGraphStateValues.intent(state, GuideGraphStateKeys.INITIAL_INTENT);
        if (initialIntent.isPresent()) {
            return new MainIntentRouteResult(decisionFromInitialIntent(initialIntent.get()), null, null, null, "INITIAL", false);
        }
        try {
            MainIntentDecision decision = routeWithIntentService(state);
            return new MainIntentRouteResult(decision, null, null, null, routeSource(decision), true);
        } catch (RuntimeException exception) {
            String errorCode = mainIntentErrorCode(exception);
            log.atWarn()
                    .addKeyValue(RagLogFields.EVENT_NAME, "main_intent.router_fallback")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_FAILURE)
                    .addKeyValue(RagLogFields.RAG_CORRELATION_ID, state.value(GuideGraphStateKeys.CORRELATION_ID, ""))
                    .addKeyValue("agent.turn_id", state.value(GuideGraphStateKeys.RUN_ID, ""))
                    .addKeyValue("main_intent.error_code", errorCode)
                    .setCause(exception)
                    .log("main intent router failed; marking turn as failed");
            return new MainIntentRouteResult(
                    MainIntentDecision.clarify("main intent router fallback"),
                    errorCode,
                    safeMessage(exception),
                    publicFailureMessage(errorCode),
                    "FALLBACK",
                    true
            );
        }
    }

    private MainIntentDecision decisionFromInitialIntent(GuideGraphIntent intent) {
        MainIntent mainIntent = toMainIntent(intent);
        return new MainIntentDecision(
                mainIntent,
                mainIntent == MainIntent.CLARIFY || mainIntent == MainIntent.UNKNOWN ? 0.0d : 1.0d,
                mainIntent == MainIntent.CLARIFY || mainIntent == MainIntent.UNKNOWN,
                isWriteAction(mainIntent),
                GuideGraphWorkflows.targetFor(mainIntent),
                "initial intent override",
                Map.of(),
                List.of()
        );
    }

    private String routeSource(MainIntentDecision decision) {
        if (decision == null) {
            return "DEFAULT";
        }
        if ("deterministic order_manage pre-router".equals(decision.reason())) {
            return "RULE";
        }
        if ("intent router service unavailable".equals(decision.reason())) {
            return "DEFAULT";
        }
        return mainIntentRouterService == null ? "DEFAULT" : "LLM";
    }

    private MainIntentDecision routeWithIntentService(OverAllState state) {
        MainIntentDecision deterministicOrderDecision = deterministicOrderRoute(state);
        if (deterministicOrderDecision != null) {
            return deterministicOrderDecision;
        }
        if (mainIntentRouterService == null) {
            log.atWarn()
                    .addKeyValue(RagLogFields.EVENT_NAME, "main_intent.router_service_unavailable")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_FAILURE)
                    .addKeyValue(RagLogFields.RAG_CORRELATION_ID, state.value(GuideGraphStateKeys.CORRELATION_ID, ""))
                    .addKeyValue("agent.turn_id", state.value(GuideGraphStateKeys.RUN_ID, ""))
                    .log("main intent router service unavailable; falling back to clarify");
            return MainIntentDecision.clarify("intent router service unavailable");
        }
        return mainIntentRouterService.route(
                requiredString(state, GuideGraphStateKeys.MESSAGE),
                conversationMemory(state)
        );
    }

    private MainIntentDecision deterministicOrderRoute(OverAllState state) {
        String message = requiredString(state, GuideGraphStateKeys.MESSAGE);
        String normalized = message.trim().toLowerCase();
        if (normalized.contains("查看") && normalized.contains("购物车")) {
            return null;
        }
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        String activePendingStatus = "";
        if (pendingOrderActionRepository != null) {
            activePendingStatus = pendingOrderActionRepository
                    .findActiveByUserIdAndConversationId(userId, conversationId)
                    .map(record -> record.status().name())
                    .orElse("");
        }
        boolean hasActivePending = !activePendingStatus.isBlank();
        boolean checkout = looksLikeOrderCheckout(normalized);
        boolean pendingFollowUp = hasActivePending
                && (looksLikeOrderConfirm(normalized) || looksLikeOrderCancel(normalized) || looksLikeAddress(normalized));
        if (!checkout && !pendingFollowUp) {
            return null;
        }
        log.atInfo()
                .addKeyValue("routeSource", "RULE")
                .addKeyValue("selectedWorkflow", GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW)
                .addKeyValue("activePendingOrderStatus", activePendingStatus)
                .addKeyValue("messagePreview", message.length() > 40 ? message.substring(0, 40) : message)
                .log("main deterministic pre-router selected order_manage_workflow");
        return new MainIntentDecision(
                MainIntent.CREATE_ORDER,
                1.0d,
                false,
                true,
                GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW,
                "deterministic order_manage pre-router",
                Map.of(),
                List.of()
        );
    }

    private boolean looksLikeOrderCheckout(String normalized) {
        return normalized.contains("结算购物车")
                || normalized.contains("我要下单")
                || normalized.contains("帮我下单")
                || normalized.contains("提交订单")
                || normalized.contains("购买购物车")
                || normalized.contains("买这些")
                || normalized.equals("checkout");
    }

    private boolean looksLikeOrderConfirm(String normalized) {
        return normalized.equals("确认")
                || normalized.equals("可以")
                || normalized.equals("没问题")
                || normalized.equals("就这样")
                || normalized.contains("确认下单")
                || normalized.contains("确认订单")
                || normalized.contains("提交订单");
    }

    private boolean looksLikeOrderCancel(String normalized) {
        return normalized.contains("取消") || normalized.contains("先不买") || normalized.contains("不要了");
    }

    private boolean looksLikeAddress(String normalized) {
        return normalized.contains("地址")
                || normalized.contains("寄到")
                || normalized.contains("送到")
                || normalized.contains("收货")
                || normalized.matches(".*\\d{8,}.*")
                || normalized.length() >= 12;
    }

    private String conversationMemory(OverAllState state) {
        List<ConversationMessage> recentMessages = state.value(
                GuideGraphStateKeys.RECENT_MESSAGES,
                List.<ConversationMessage>of()
        );
        if (recentMessages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ConversationMessage message : recentMessages) {
            builder.append(message.role()).append(": ").append(message.content()).append('\n');
        }
        return builder.toString().trim();
    }

    private GuideGraphIntent toGuideIntent(MainIntent intent) {
        if (intent == null) {
            return GuideGraphIntent.UNKNOWN;
        }
        try {
            return GuideGraphIntent.valueOf(intent.name());
        } catch (IllegalArgumentException ignored) {
            return GuideGraphIntent.UNKNOWN;
        }
    }

    private MainIntent toMainIntent(GuideGraphIntent intent) {
        if (intent == null) {
            return MainIntent.UNKNOWN;
        }
        try {
            return MainIntent.valueOf(intent.name());
        } catch (IllegalArgumentException ignored) {
            return MainIntent.UNKNOWN;
        }
    }

    private boolean isWriteAction(MainIntent intent) {
        return switch (intent) {
            case ADD_TO_CART, REMOVE_FROM_CART, UPDATE_CART_ITEM, CART_MANAGE,
                 CREATE_ORDER, CONFIRM_ORDER, CANCEL_ORDER -> true;
            default -> false;
        };
    }

    /**
     * Real (non-placeholder) actions for specific workflow nodes. Keys not present in this map
     * fall back to {@link #workflowNode(String)}'s mock implementation.
     */
    private Map<String, GuideGraphNodeAction> defaultWorkflowActions() {
        Map<String, GuideGraphNodeAction> actions = new LinkedHashMap<>();
        if (cartManageWorkflowNode != null) {
            actions.put(GuideGraphNodeNames.CART_MANAGE_WORKFLOW, cartManageWorkflowNode::execute);
        }
        actions.put(GuideGraphNodeNames.CLARIFY_WORKFLOW, this::clarifyWorkflow);
        return actions;
    }

    private GuideNodeExecutionResult clarifyWorkflow(OverAllState state) {
        String message = MAIN_INTENT_LLM_TIMEOUT.equals(state.value(GuideGraphStateKeys.ERROR_CODE, ""))
                || MAIN_INTENT_ROUTER_FAILED.equals(state.value(GuideGraphStateKeys.ERROR_CODE, ""))
                ? "意图识别服务暂时超时，请重新发送一次。"
                : CLARIFY_MESSAGE;
        message = state.value(GuideGraphStateKeys.NODE_MESSAGE)
                .map(Object::toString)
                .filter(value -> !value.isBlank())
                .orElse(message);
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(GuideGraphStateKeys.NODE_MESSAGE, message);
        updates.put(GuideGraphStateKeys.NEED_USER_INPUT, true);
        updates.put(GuideGraphStateKeys.WORKFLOW_STATUS, NodeRunStatus.WAITING_CLARIFICATION.name());
        return new GuideNodeExecutionResult(
                NodeRunStatus.WAITING_CLARIFICATION,
                null,
                NodeRunStatus.WAITING_CLARIFICATION,
                updates,
                Map.of("needUserInput", true)
        );
    }

    private GuideGraphNodeAction workflowNode(String nodeName) {
        return state -> GuideNodeExecutionResult.workflow(
                Map.of(
                        "intent", state.value(GuideGraphStateKeys.MAIN_INTENT, GuideGraphIntent.CLARIFY.name()),
                        "workflow", nodeName,
                        "message", "Workflow is not implemented yet: " + nodeName,
                        "todo", true
                ),
                Map.of("todo", true, "workflow", nodeName)
        );
    }

    private GuideNodeExecutionResult orderManageWorkflow(OverAllState state) {
        if (orderManageSubgraphFactory == null) {
            throw new IllegalStateException("OrderManageSubgraphFactory is not configured");
        }
        OverAllState finalState;
        try {
            finalState = orderManageSubgraphFactory.build()
                    .compile()
                    .invoke(state.data())
                    .orElseThrow(() -> new IllegalStateException("Order manage subgraph completed without final state"));
        } catch (Exception exception) {
            throw new IllegalStateException("order_manage_workflow dispatch failed", exception);
        }
        Map<String, Object> updates = new LinkedHashMap<>(finalState.data());
        updates.remove(GuideGraphStateKeys.LAST_NODE_RESULT);
        updates.remove(GuideGraphStateKeys.NODE_RESULTS);
        updates.remove(GuideGraphStateKeys.GRAPH_STATUS);
        updates.remove(GuideGraphStateKeys.ANSWER_CONTEXT);
        updates.put(GuideGraphStateKeys.ORDER_WORKFLOW_DISPATCHED, true);
        String orderStatus = finalState.value(OrderManageStateKeys.ORDER_STATUS, "");
        String nodeMessage = finalState.value(OrderManageStateKeys.NODE_MESSAGE, "");
        NodeRunStatus statusOverride = orderNodeStatus(orderStatus);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(GuideGraphStateKeys.ORDER_WORKFLOW_DISPATCHED, true);
        putIfPresent(metadata, OrderManageStateKeys.ORDER_STATUS, orderStatus);
        finalState.value(OrderManageStateKeys.ORDER_ACTION)
                .ifPresent(value -> metadata.put(OrderManageStateKeys.ORDER_ACTION, value));
        if (statusOverride == NodeRunStatus.FAILED) {
            updates.put(GuideGraphStateKeys.ERROR_CODE, "ORDER_WORKFLOW_FAILED");
            updates.put(GuideGraphStateKeys.ERROR_MESSAGE, nodeMessage.isBlank()
                    ? "下单流程执行失败，请稍后重试。"
                    : nodeMessage);
            metadata.put(GuideGraphStateKeys.ERROR_CODE, "ORDER_WORKFLOW_FAILED");
            metadata.put(GuideGraphStateKeys.ERROR_MESSAGE, updates.get(GuideGraphStateKeys.ERROR_MESSAGE));
        }
        return new GuideNodeExecutionResult(statusOverride, null, null, updates, metadata);
    }

    private NodeRunStatus orderNodeStatus(String orderStatus) {
        if (OrderManageStatus.FAILED.name().equals(orderStatus)
                || OrderManageStatus.EXPIRED.name().equals(orderStatus)) {
            return NodeRunStatus.FAILED;
        }
        return null;
    }

    private GuideNodeExecutionResult buildAnswerContext(OverAllState state) {
        Map<String, Object> answerContext = new LinkedHashMap<>();
        answerContext.put("intent", GuideGraphStateValues.intent(state, GuideGraphStateKeys.INTENT)
                .orElse(GuideGraphIntent.CLARIFY).name());
        String targetWorkflow = state.value(GuideGraphStateKeys.TARGET_WORKFLOW, GuideGraphNodeNames.CLARIFY_WORKFLOW);
        answerContext.put("targetWorkflow", targetWorkflow);
        state.value(GuideGraphStateKeys.WORKFLOW_RESULT).ifPresent(workflowResult ->
                answerContext.put("workflowResult", workflowResult));

        if (GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW.equals(targetWorkflow) && !orderWorkflowDispatched(state)) {
            answerContext.put("answer", ORDER_WORKFLOW_NOT_DISPATCHED_MESSAGE);
            answerContext.put("todo", false);
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(GuideGraphStateKeys.ANSWER_CONTEXT, Map.copyOf(answerContext));
            updates.put(GuideGraphStateKeys.ERROR_CODE, ORDER_WORKFLOW_NOT_DISPATCHED);
            updates.put(GuideGraphStateKeys.ERROR_MESSAGE, ORDER_WORKFLOW_NOT_DISPATCHED_MESSAGE);
            updates.put(GuideGraphStateKeys.NODE_MESSAGE, ORDER_WORKFLOW_NOT_DISPATCHED_MESSAGE);
            return new GuideNodeExecutionResult(
                    NodeRunStatus.FAILED,
                    null,
                    null,
                    updates,
                    Map.of(
                            "answerReady", true,
                            "todo", false,
                            GuideGraphStateKeys.ERROR_CODE, ORDER_WORKFLOW_NOT_DISPATCHED,
                            GuideGraphStateKeys.ERROR_MESSAGE, ORDER_WORKFLOW_NOT_DISPATCHED_MESSAGE
                    )
            );
        }

        String answer = explicitNodeMessage(state, targetWorkflow)
                .orElseGet(() -> fallbackAnswer(state));

        answerContext.put("answer", answer);
        answerContext.put("todo", false);
        return GuideNodeExecutionResult.withStateUpdates(
                Map.of(GuideGraphStateKeys.ANSWER_CONTEXT, Map.copyOf(answerContext)),
                Map.of("answerReady", true, "todo", false)
        );
    }

    private String fallbackAnswer(OverAllState state) {
        if (hasFailedState(state) || hasStateErrorCode(state)) {
            return FAILURE_MESSAGE;
        }
        if (isClarifyState(state)) {
            return CLARIFY_MESSAGE;
        }
        Object workflowResult = state.value(GuideGraphStateKeys.WORKFLOW_RESULT).orElse(null);
        if (workflowResult instanceof CartManageWorkflowResult cartResult) {
            return cartManageAnswer(cartResult, state.value(GuideGraphStateKeys.CLARIFY_REASON, ""));
        }
        Object businessResult = state.value(GuideGraphStateKeys.BUSINESS_RESULT).orElse(null);
        if (businessResult instanceof Map<?, ?> map) {
            Object errorMessage = map.get("errorMessage");
            if (errorMessage != null && !String.valueOf(errorMessage).isBlank()) {
                return String.valueOf(errorMessage);
            }
        }
        if (hasSuccessfulWorkflowStatus(state)) {
            return "处理完成。";
        }
        return NO_BUSINESS_RESULT_MESSAGE;
    }

    private String cartManageAnswer(CartManageWorkflowResult result, String clarifyReason) {
        if (result.clarifyQuestion() != null && !result.clarifyQuestion().isBlank()) {
            return result.clarifyQuestion();
        }
        if (result.errorMessage() != null && !result.errorMessage().isBlank()) {
            return result.errorMessage();
        }
        if (result.action() == null) {
            return NO_BUSINESS_RESULT_MESSAGE;
        }
        return switch (result.action()) {
            case ADD -> addSuccessAnswer(result);
            case REMOVE_ITEM -> "已从购物车删除该商品。";
            case UPDATE_QUANTITY -> "已更新购物车商品数量。";
            case CLEAR_CART -> "你确认要清空整个购物车吗？这个操作不可撤销。";
            case VIEW_CART -> "这是你当前购物车中的商品。";
            case UNKNOWN -> clarifyFallback(clarifyReason);
        };
    }

    private String addSuccessAnswer(CartManageWorkflowResult result) {
        ProductCandidate candidate = result.productCandidates().isEmpty()
                ? null
                : result.productCandidates().getFirst();
        String productName = candidate == null || candidate.productName() == null
                ? "该商品"
                : candidate.productName();
        int quantity = result.slots() == null || result.slots().quantity() == null
                ? 1
                : Math.max(result.slots().quantity(), 1);
        return "已将「" + productName + "」加入购物车，数量 " + quantity + "。";
    }

    private String clarifyFallback(String clarifyReason) {
        return switch (clarifyReason == null ? "" : clarifyReason) {
            case "product_not_found" -> "我没有找到你说的商品，请换个商品名或提供更具体的品牌、型号、尺寸。";
            case "missing_quantity" -> "请告诉我要加入几件。";
            default -> "我需要再确认一下你的操作。";
        };
    }

    private String routeByMockIntent(OverAllState state) {
        return state.value(GuideGraphStateKeys.TARGET_WORKFLOW, GuideGraphNodeNames.CLARIFY_WORKFLOW);
    }

    private String routeByConversationExists(OverAllState state) {
        boolean exists = state.value(GuideGraphStateKeys.CONVERSATION_EXISTS, false);
        return exists ? GuideGraphNodeNames.LOAD_MEMORY : GuideGraphNodeNames.INIT_CONVERSATION;
    }

    private Map<String, String> conversationExistenceMappings() {
        return Map.of(
                GuideGraphNodeNames.LOAD_MEMORY, GuideGraphNodeNames.LOAD_MEMORY,
                GuideGraphNodeNames.INIT_CONVERSATION, GuideGraphNodeNames.INIT_CONVERSATION
        );
    }

    private Map<String, String> workflowMappings() {
        Map<String, String> mappings = new LinkedHashMap<>();
        for (String nodeName : GuideGraphWorkflows.targets().values()) {
            mappings.put(nodeName, nodeName);
        }
        return mappings;
    }

    private String requiredString(OverAllState state, String key) {
        return state.value(key)
                .map(Object::toString)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Missing graph state: " + key));
    }

    private Optional<String> explicitNodeMessage(OverAllState state, String targetWorkflow) {
        if (GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW.equals(targetWorkflow)
                && state.value(OrderManageStateKeys.NODE_MESSAGE).isPresent()) {
            return nonBlankStateString(state, OrderManageStateKeys.NODE_MESSAGE);
        }
        if (GuideGraphNodeNames.CART_MANAGE_WORKFLOW.equals(targetWorkflow)
                && state.value(com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys.NODE_MESSAGE).isPresent()) {
            return nonBlankStateString(state, com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys.NODE_MESSAGE);
        }
        if (GuideGraphNodeNames.PRODUCT_QUERY_WORKFLOW.equals(targetWorkflow)
                && state.value(ProductQueryGraphStateKeys.NODE_MESSAGE).isPresent()) {
            return nonBlankStateString(state, ProductQueryGraphStateKeys.NODE_MESSAGE);
        }
        Optional<String> orderMessage = nonBlankStateString(state, OrderManageStateKeys.NODE_MESSAGE);
        if (orderMessage.isPresent()) {
            return orderMessage;
        }
        Optional<String> productQueryMessage = nonBlankStateString(state, ProductQueryGraphStateKeys.NODE_MESSAGE);
        if (productQueryMessage.isPresent()) {
            return productQueryMessage;
        }
        return nonBlankStateString(state, GuideGraphStateKeys.NODE_MESSAGE);
    }

    private void emitWorkflowStarted(
            java.util.function.Consumer<AgentStreamEvent> eventSink,
            OverAllState state,
            Map<String, Object> routerResult
    ) {
        if (eventSink == null || routerResult == null) {
            return;
        }
        Object targetWorkflow = routerResult.get(GuideGraphStateKeys.TARGET_WORKFLOW);
        if (targetWorkflow == null) {
            return;
        }
        Object intent = routerResult.get(GuideGraphStateKeys.INTENT);
        String correlationId = state.value(GuideGraphStateKeys.CORRELATION_ID, "");
        eventSink.accept(GuideGraphStreamEvents.workflowStarted(
                correlationId,
                String.valueOf(targetWorkflow),
                intent == null ? null : String.valueOf(intent)
        ));
    }

    private Optional<String> nonBlankStateString(OverAllState state, String key) {
        return state.value(key).map(Object::toString).filter(value -> !value.isBlank());
    }

    private boolean orderWorkflowDispatched(OverAllState state) {
        return state.value(GuideGraphStateKeys.ORDER_WORKFLOW_DISPATCHED, false)
                || state.value(OrderManageStateKeys.ORDER_ACTION).isPresent()
                || state.value(OrderManageStateKeys.ORDER_STATUS).isPresent()
                || state.value(OrderManageStateKeys.NODE_MESSAGE).isPresent();
    }

    private boolean hasFailedState(OverAllState state) {
        if (NodeRunStatus.FAILED.name().equals(state.value(GuideGraphStateKeys.GRAPH_STATUS, ""))) {
            return true;
        }
        if ("FAILED".equals(state.value(GuideGraphStateKeys.WORKFLOW_STATUS, ""))) {
            return true;
        }
        if ("FAILED".equals(state.value(OrderManageStateKeys.ORDER_STATUS, ""))) {
            return true;
        }
        List<GuideNodeResult> nodeResults = state.value(GuideGraphStateKeys.NODE_RESULTS, List.<GuideNodeResult>of());
        return nodeResults.stream()
                .anyMatch(result -> result != null && result.status() == NodeRunStatus.FAILED);
    }

    private boolean hasStateErrorCode(OverAllState state) {
        return state.value(GuideGraphStateKeys.ERROR_CODE)
                .map(Object::toString)
                .filter(value -> !value.isBlank())
                .isPresent();
    }

    private boolean isClarifyState(OverAllState state) {
        String targetWorkflow = state.value(GuideGraphStateKeys.TARGET_WORKFLOW, "");
        if (GuideGraphNodeNames.CLARIFY_WORKFLOW.equals(targetWorkflow)) {
            return true;
        }
        return GuideGraphStateValues.intent(state, GuideGraphStateKeys.INTENT)
                .filter(intent -> intent == GuideGraphIntent.CLARIFY || intent == GuideGraphIntent.UNKNOWN)
                .isPresent();
    }

    private boolean hasSuccessfulWorkflowStatus(OverAllState state) {
        String workflowStatus = state.value(GuideGraphStateKeys.WORKFLOW_STATUS, "");
        return NodeRunStatus.SUCCESS.name().equals(workflowStatus) || workflowStatus.endsWith("_SUCCESS");
    }

    private void putIfPresent(Map<String, Object> updates, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            updates.put(key, value);
        }
    }

    private String mainIntentErrorCode(Throwable exception) {
        return hasCause(exception, SocketTimeoutException.class) || hasCause(exception, RestClientException.class)
                ? MAIN_INTENT_LLM_TIMEOUT
                : MAIN_INTENT_ROUTER_FAILED;
    }

    private String publicFailureMessage(String errorCode) {
        return MAIN_INTENT_LLM_TIMEOUT.equals(errorCode)
                ? "当前请求处理超时，请稍后重试。"
                : "当前请求处理失败，请稍后重试。";
    }

    private boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        Throwable current = exception;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String safeMessage(Throwable exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private record MainIntentRouteResult(
            MainIntentDecision decision,
            String errorCode,
            String errorMessage,
            String nodeMessage,
            String routeSource,
            Boolean llmCalled
    ) {
    }
}
