package com.bytedance.ai.graph.cartmanage.subgraph;

import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.orchestration.GuideGraphStateKeys;
import com.bytedance.ai.graph.cartmanage.application.CartManageSlotFillingService;
import com.bytedance.ai.graph.cartmanage.CartManageSlots;
import com.bytedance.ai.graph.cartmanage.application.CartCommandService;
import com.bytedance.ai.graph.cartmanage.CartMutationResult;
import com.bytedance.ai.graph.cartmanage.application.CartQueryService;
import com.bytedance.ai.graph.cartmanage.application.InventoryQueryService;
import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import com.bytedance.ai.graph.cartmanage.application.ProductCatalogResolver;
import com.bytedance.ai.graph.cartmanage.StockResult;
import com.bytedance.ai.graph.cartmanage.persistence.PendingCartActionRecord;
import com.bytedance.ai.graph.cartmanage.persistence.PendingCartActionRepository;
import com.bytedance.ai.graph.conversation.ConversationMessage;
import com.bytedance.ai.graph.intent.support.SlotKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CartManageSubgraphFactory {

    private static final Logger log = LoggerFactory.getLogger(CartManageSubgraphFactory.class);
    private static final List<String> COLOR_WORDS = List.of(
            "黑色", "黑", "灰色", "灰", "蓝色", "蓝", "白色", "白", "红色", "红",
            "绿色", "绿", "黄色", "黄", "粉色", "粉", "紫色", "紫", "藏青",
            "米色", "棕色", "咖色", "银色", "金色"
    );
    private static final Pattern SIZE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:寸|英寸|inch|in)\\b?", Pattern.CASE_INSENSITIVE);
    private static final List<Pattern> PRICE_PATTERNS = List.of(
            Pattern.compile("(?:商品)?价格\\s*(?:为|是|=|：|:)?\\s*[¥￥]?\\s*(\\d+(?:\\.\\d{1,2})?)"),
            Pattern.compile("(?:预算|价位)\\s*(?:为|是|=|：|:)?\\s*[¥￥]?\\s*(\\d+(?:\\.\\d{1,2})?)"),
            Pattern.compile("[¥￥]\\s*(\\d+(?:\\.\\d{1,2})?)"),
            Pattern.compile("(\\d+(?:\\.\\d{1,2})?)\\s*元\\s*(?:的那个|那个|这款|的)?")
    );

    private final CartQueryService cartQueryService;
    private final CartCommandService cartCommandService;
    private final InventoryQueryService inventoryQueryService;
    private final ProductCatalogResolver productCatalogResolver;
    private final PendingCartActionRepository pendingCartActionRepository;
    private final CartManageSlotFillingService slotFillingService;
    private final CandidateSelectionLlmService candidateSelectionLlmService;

    public CartManageSubgraphFactory(
            CartQueryService cartQueryService,
            CartCommandService cartCommandService,
            InventoryQueryService inventoryQueryService,
            ProductCatalogResolver productCatalogResolver,
            PendingCartActionRepository pendingCartActionRepository,
            CartManageSlotFillingService slotFillingService,
            CandidateSelectionLlmService candidateSelectionLlmService
    ) {
        this.cartQueryService = cartQueryService;
        this.cartCommandService = cartCommandService;
        this.inventoryQueryService = inventoryQueryService;
        this.productCatalogResolver = productCatalogResolver;
        this.pendingCartActionRepository = pendingCartActionRepository;
        this.slotFillingService = slotFillingService;
        this.candidateSelectionLlmService = candidateSelectionLlmService;
    }

    public StateGraph build() {
        try {
            return buildInternal();
        } catch (com.alibaba.cloud.ai.graph.exception.GraphStateException ex) {
            throw new IllegalStateException("cart_manage_subgraph compile failed", ex);
        }
    }

    private StateGraph buildInternal() throws com.alibaba.cloud.ai.graph.exception.GraphStateException {
        StateGraph subgraph = new StateGraph("cart_manage_subgraph", keyStrategyFactory());

        subgraph.addNode("cart_load_context", AsyncNodeAction.node_async(this::cartLoadContext));
        subgraph.addNode("cart_resolve_action", AsyncNodeAction.node_async(this::cartResolveAction));
        subgraph.addNode("cart_resolve_target", AsyncNodeAction.node_async(this::cartResolveTarget));
        subgraph.addNode("cart_search_catalog", AsyncNodeAction.node_async(this::cartSearchCatalog));
        subgraph.addNode("cart_resolve_candidate", AsyncNodeAction.node_async(this::cartResolveCandidate));
        subgraph.addNode("cart_check_stock", AsyncNodeAction.node_async(this::cartCheckStock));
        subgraph.addNode("cart_execute_action", AsyncNodeAction.node_async(this::cartExecuteAction));
        subgraph.addNode("cart_final_response", AsyncNodeAction.node_async(this::cartFinalResponse));

        subgraph.addEdge(StateGraph.START, "cart_load_context");
        subgraph.addEdge("cart_load_context", "cart_resolve_action");

        subgraph.addConditionalEdges(
                "cart_resolve_action",
                AsyncEdgeAction.edge_async(this::routeAfterResolveAction),
                Map.of(
                        "VIEW", "cart_execute_action",
                        "CLEAR", "cart_execute_action",
                        "CONFIRM", "cart_resolve_candidate",
                        "ADD_REMOVE_UPDATE", "cart_resolve_target",
                        "UNKNOWN", "cart_final_response"
                )
        );

        subgraph.addConditionalEdges(
                "cart_resolve_target",
                AsyncEdgeAction.edge_async(this::routeAfterResolveTarget),
                Map.of(
                        "HAS_IDS", "cart_check_stock",
                        "ADD_SEARCH", "cart_search_catalog",
                        "REMOVE_UPDATE_EXECUTE", "cart_execute_action",
                        "FINAL", "cart_final_response"
                )
        );

        subgraph.addConditionalEdges(
                "cart_search_catalog",
                AsyncEdgeAction.edge_async(this::routeAfterSearchCatalog),
                Map.of(
                        "NO_CANDIDATES", "cart_final_response",
                        "ONE_CANDIDATE", "cart_check_stock",
                        "MULTI_CANDIDATES", "cart_final_response"
                )
        );

        subgraph.addConditionalEdges(
                "cart_resolve_candidate",
                AsyncEdgeAction.edge_async(this::routeAfterResolveCandidate),
                Map.of(
                        "HAS_IDS", "cart_check_stock",
                        "FINAL", "cart_final_response"
                )
        );

        subgraph.addConditionalEdges(
                "cart_check_stock",
                AsyncEdgeAction.edge_async(this::routeAfterCheckStock),
                Map.of(
                        "IN_STOCK", "cart_execute_action",
                        "OUT_OF_STOCK", "cart_final_response"
                )
        );

        subgraph.addEdge("cart_execute_action", "cart_final_response");
        subgraph.addEdge("cart_final_response", StateGraph.END);

        return subgraph;
    }

    private KeyStrategyFactory keyStrategyFactory() {
        return new KeyStrategyFactoryBuilder()
                .defaultStrategy(new ReplaceStrategy())
                .build();
    }

    private void clearTransientCartState(Map<String, Object> updates) {
        for (String key : List.of(
                CartGraphStateKeys.CART_ACTION,
                CartGraphStateKeys.CART_STATUS,
                CartGraphStateKeys.PRODUCT_ID,
                CartGraphStateKeys.SKU_ID,
                CartGraphStateKeys.PRODUCT_NAME,
                CartGraphStateKeys.EXPECTED_PRICE,
                CartGraphStateKeys.QUANTITY,
                CartGraphStateKeys.ITEM_INDEX,
                CartGraphStateKeys.CONTEXTUAL_REFERENCE,
                CartGraphStateKeys.PRODUCT_CANDIDATES,
                CartGraphStateKeys.SELECTED_CANDIDATE,
                CartGraphStateKeys.PENDING_CART_ACTION_ID,
                CartGraphStateKeys.STOCK_RESULT,
                CartGraphStateKeys.CART_RESULT,
                CartGraphStateKeys.WORKFLOW_STATUS,
                CartGraphStateKeys.CLARIFY_REASON,
                CartGraphStateKeys.NEED_USER_INPUT,
                CartGraphStateKeys.NODE_MESSAGE
        )) {
            updates.put(key, OverAllState.MARK_FOR_REMOVAL);
        }
    }

    private Map<String, Object> cartLoadContext(OverAllState state) {
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        String userMessage = state.value(GuideGraphStateKeys.MESSAGE, "");

        Map<String, Object> updates = new LinkedHashMap<>();
        clearTransientCartState(updates);
        log.info("Cart subgraph load context start: userId={}, conversationId={}, messageLength={}",
                userId, conversationId, userMessage == null ? 0 : userMessage.length());
        updates.put(GuideGraphStateKeys.USER_ID, userId);
        updates.put(GuideGraphStateKeys.CONVERSATION_ID, conversationId);
        updates.put(GuideGraphStateKeys.MESSAGE, userMessage);

        // Pending selection turns are resolved inside cart_resolve_candidate. Keep the pending
        // action for natural language selectors such as "黑色的"; cancel only when the new turn
        // clearly starts another cart operation.
        Optional<PendingCartActionRecord> pending = pendingCartActionRepository
                .findActiveByUserIdAndConversationId(userId, conversationId);
        boolean pendingLoaded = false;
        boolean stalePendingCancelled = false;
        if (pending.isPresent()) {
            if (looksLikeCandidateSelection(userMessage, pending.get().candidates())
                    || !looksLikeNewCartRequest(userMessage)) {
                updates.put(CartGraphStateKeys.PENDING_CART_ACTION_ID, pending.get().id());
                updates.put(CartGraphStateKeys.PRODUCT_CANDIDATES, pending.get().candidates());
                pendingLoaded = true;
                log.info("Loaded pending cart action {} for user {} conversation {} (selection follow-up)",
                        pending.get().id(), userId, conversationId);
            } else {
                pendingCartActionRepository.markCancelled(pending.get().id());
                stalePendingCancelled = true;
                log.info("Cancelled stale pending cart action {} (new non-selection turn)",
                        pending.get().id());
            }
        }

        log.info("Cart subgraph load context done: userId={}, conversationId={}, pendingLoaded={}, stalePendingCancelled={}",
                userId,
                conversationId,
                pendingLoaded,
                stalePendingCancelled);
        return updates;
    }

    private static final Pattern SELECTION_PATTERN = Pattern.compile(
            "^\\s*(?:我)?\\s*(?:选|选择|要|想要|就)?\\s*(?:第)?\\s*([1-5一二三四五])\\s*(?:个|款|号)?\\s*(?:吧)?\\s*$"
    );

    private boolean looksLikeCandidateSelection(String message) {
        return looksLikeCandidateSelection(message, List.of());
    }

    private boolean looksLikeCandidateSelection(String message, List<ProductCandidate> candidates) {
        if (message == null) {
            return false;
        }
        return isImplicitThisSelection(message)
                || parseSelectionIndex(message, Math.max(1, candidates == null ? 5 : candidates.size())) > 0
                || (candidates != null && attributeMatch(message, candidates).status() != CandidateSelectionStatus.UNMATCHED);
    }

    private Map<String, Object> cartResolveAction(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        String userMessage = state.value(GuideGraphStateKeys.MESSAGE, "");
        Map<String, Object> intentSlots = readIntentSlots(state);

        CartAction action = CartAction.UNKNOWN;
        boolean pendingSelection = state.value(CartGraphStateKeys.PENDING_CART_ACTION_ID).isPresent();
        log.info("Cart resolve action start: pendingSelection={}, slotKeys={}",
                pendingSelection, intentSlots.keySet());

        if (pendingSelection) {
            action = CartAction.CONFIRM;
        } else {
            String mainCartAction = asString(intentSlots.get(SlotKeys.CART_ACTION));
            if (StringUtils.hasText(mainCartAction)) {
                action = parseCartAction(mainCartAction);
            }
            if (action == CartAction.UNKNOWN) {
                action = inferActionFromMessage(userMessage);
            }
        }

        updates.put(CartGraphStateKeys.CART_ACTION, action.name());
        log.info("Cart resolve action done: action={}", action);
        return updates;
    }

    private String routeAfterResolveAction(OverAllState state) {
        String actionName = state.value(CartGraphStateKeys.CART_ACTION, CartAction.UNKNOWN.name());
        CartAction action = safeCartAction(actionName);
        String route;

        if (action == CartAction.VIEW) {
            route = "VIEW";
        } else if (action == CartAction.CLEAR) {
            route = "CLEAR";
        } else if (action == CartAction.CONFIRM) {
            route = "CONFIRM";
        } else if (action == CartAction.ADD || action == CartAction.REMOVE || action == CartAction.UPDATE_QUANTITY) {
            route = "ADD_REMOVE_UPDATE";
        } else {
            route = "UNKNOWN";
        }
        log.info("Cart route after resolve action: action={}, route={}", action, route);
        return route;
    }

    private Map<String, Object> cartResolveTarget(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        String userMessage = state.value(GuideGraphStateKeys.MESSAGE, "");
        Map<String, Object> intentSlots = readIntentSlots(state);

        CartAction action = safeCartAction(
                state.value(CartGraphStateKeys.CART_ACTION, CartAction.UNKNOWN.name())
        );
        log.info("Cart resolve target start: action={}, userId={}, conversationId={}",
                action, userId, conversationId);

        CartManageSlots filledSlots = slotFillingService.extract(userMessage, conversationMemory(state));
        String productName = firstNonBlank(
                asString(intentSlots.get(SlotKeys.PRODUCT_NAME)),
                filledSlots.productName()
        );
        String productId = firstNonBlank(
                asString(intentSlots.get(SlotKeys.PRODUCT_ID)),
                asString(intentSlots.get(SlotKeys.PRODUCT_REF)),
                filledSlots.productId()
        );
        String skuId = firstNonBlank(asString(intentSlots.get(SlotKeys.SKU_ID)), filledSlots.skuId());
        BigDecimal expectedPrice = firstNonNull(
                asBigDecimal(intentSlots.get(SlotKeys.EXPECTED_PRICE)),
                filledSlots.expectedPrice(),
                extractExpectedPrice(userMessage)
        );
        Integer quantity = firstNonNull(asInteger(intentSlots.get(SlotKeys.QUANTITY)), filledSlots.quantity());
        Integer itemIndex = firstNonNull(asInteger(intentSlots.get(SlotKeys.ITEM_INDEX)), filledSlots.itemIndex());
        Boolean contextualReference = firstNonNull(
                Boolean.TRUE.equals(intentSlots.get(SlotKeys.CONTEXTUAL_REFERENCE)) ? Boolean.TRUE : null,
                filledSlots.contextualReference()
        );

        if (quantity == null || quantity < 1) {
            quantity = 1;
        }

        updates.put(CartGraphStateKeys.PRODUCT_NAME, productName);
        updates.put(CartGraphStateKeys.PRODUCT_ID, productId);
        updates.put(CartGraphStateKeys.SKU_ID, skuId);
        updates.put(CartGraphStateKeys.EXPECTED_PRICE, expectedPrice);
        updates.put(CartGraphStateKeys.QUANTITY, quantity);
        updates.put(CartGraphStateKeys.ITEM_INDEX, itemIndex);
        updates.put(CartGraphStateKeys.CONTEXTUAL_REFERENCE, contextualReference);

        if ((action == CartAction.REMOVE || action == CartAction.UPDATE_QUANTITY)
                && !hasCartItemTarget(itemIndex, productName, productId, skuId)) {
            updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.WAITING_CLARIFICATION.name());
            updates.put(CartGraphStateKeys.NODE_MESSAGE,
                    "请说明要操作购物车中的第几个商品，例如“删除第 1 个”或“把第 2 个改成 3 件”。");
            updates.put(CartGraphStateKeys.NEED_USER_INPUT, true);
        }

        log.info("Resolved target - productName: {}, productId: {}, skuId: {}, expectedPrice: {}, quantity: {}, itemIndex: {}",
                productName, productId, skuId, expectedPrice, quantity, itemIndex);
        log.info("Cart resolve target done: action={}, hasTarget={}, status={}, needUserInput={}",
                action,
                hasCartItemTarget(itemIndex, productName, productId, skuId),
                updates.get(CartGraphStateKeys.WORKFLOW_STATUS),
                updates.get(CartGraphStateKeys.NEED_USER_INPUT));
        return updates;
    }

    private String routeAfterResolveTarget(OverAllState state) {
        CartAction action = safeCartAction(
                state.value(CartGraphStateKeys.CART_ACTION, CartAction.UNKNOWN.name())
        );

        String productId = state.<String>value(CartGraphStateKeys.PRODUCT_ID).orElse(null);
        String skuId = state.<String>value(CartGraphStateKeys.SKU_ID).orElse(null);

        String route = "FINAL";
        if (StringUtils.hasText(productId) && StringUtils.hasText(skuId)) {
            route = "HAS_IDS";
        } else if (action == CartAction.ADD) {
            String productName = state.value(CartGraphStateKeys.PRODUCT_NAME, "");
            if (StringUtils.hasText(productName)) {
                route = "ADD_SEARCH";
            }
        } else if (action == CartAction.REMOVE || action == CartAction.UPDATE_QUANTITY) {
            Integer itemIndex = state.<Integer>value(CartGraphStateKeys.ITEM_INDEX).orElse(null);
            String productName = state.value(CartGraphStateKeys.PRODUCT_NAME, "");
            if (hasCartItemTarget(itemIndex, productName, productId, skuId)) {
                route = "REMOVE_UPDATE_EXECUTE";
            }
        }

        log.info("Cart route after resolve target: action={}, productId={}, skuId={}, route={}",
                action, productId, skuId, route);
        return route;
    }

    private Map<String, Object> cartSearchCatalog(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        String productName = state.value(CartGraphStateKeys.PRODUCT_NAME, "");
        String userMessage = state.value(GuideGraphStateKeys.MESSAGE, "");
        int quantity = state.value(CartGraphStateKeys.QUANTITY, 1);
        BigDecimal expectedPrice = state.value(CartGraphStateKeys.EXPECTED_PRICE, BigDecimal.class)
                .orElseGet(() -> extractExpectedPrice(userMessage));
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);

        log.info("Cart search catalog start: userId={}, conversationId={}, productName={}, expectedPrice={}, quantity={}",
                userId, conversationId, productName, expectedPrice, quantity);
        List<ProductCandidate> candidates = productCatalogResolver.searchCandidates(productName, 5);
        CartCandidateConstraints constraints = CartCandidateConstraints.from(
                userMessage,
                productName,
                state.value(CartGraphStateKeys.PRODUCT_ID, ""),
                state.value(CartGraphStateKeys.SKU_ID, ""),
                quantity,
                expectedPrice
        );
        CartCandidateFilterResult filterResult = filterCandidatesByConstraints(candidates, constraints);
        List<ProductCandidate> matchedCandidates = filterResult.matchedCandidates();
        updates.put(CartGraphStateKeys.PRODUCT_CANDIDATES, matchedCandidates);
        if (expectedPrice != null) {
            updates.put(CartGraphStateKeys.EXPECTED_PRICE, expectedPrice);
        }

        if (candidates.isEmpty()) {
            updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.PRODUCT_NOT_FOUND.name());
            updates.put(CartGraphStateKeys.NODE_MESSAGE, "没有找到该商品，请换个关键词。");
            updates.put(CartGraphStateKeys.NEED_USER_INPUT, true);
        } else if (matchedCandidates.isEmpty()) {
            updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.PRODUCT_CONSTRAINT_NOT_MATCHED.name());
            updates.put(CartGraphStateKeys.NODE_MESSAGE,
                    constraintMismatchMessage(productName, constraints, candidates));
            updates.put(CartGraphStateKeys.NEED_USER_INPUT, true);
        } else if (matchedCandidates.size() == 1) {
            ProductCandidate only = matchedCandidates.getFirst();
            updates.put(CartGraphStateKeys.PRODUCT_ID, only.productId());
            updates.put(CartGraphStateKeys.SKU_ID, only.skuId());
            updates.put(CartGraphStateKeys.PRODUCT_NAME, only.productName());
            updates.put(CartGraphStateKeys.SELECTED_CANDIDATE, only);
            updates.put(CartGraphStateKeys.CART_STATUS, "PRODUCT_SELECTED");
        } else {
            PendingCartActionRecord pending = new PendingCartActionRecord(
                    null,
                    userId,
                    conversationId,
                    CartAction.ADD,
                    productName,
                    quantity,
                    matchedCandidates,
                    CartWorkflowStatus.WAITING_USER_SELECTION,
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    LocalDateTime.now().plusHours(24)
            );
            PendingCartActionRecord saved = pendingCartActionRepository.save(pending);
            updates.put(CartGraphStateKeys.PENDING_CART_ACTION_ID, saved.id());
            updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.WAITING_USER_SELECTION.name());
            updates.put(CartGraphStateKeys.NODE_MESSAGE, formatCandidateQuestion(matchedCandidates));
            updates.put(CartGraphStateKeys.NEED_USER_INPUT, true);
            log.info("Cart search catalog pending created: pendingId={}, candidates={}",
                    saved.id(), matchedCandidates.size());
        }

        log.info("Cart search catalog done: productName={}, expectedPrice={}, originalCandidateCount={}, matchedCandidateCount={}, priceMatchedCount={}, specMatchedCount={}, candidatePrices={}, mismatchReasons={}, route={}, status={}, needUserInput={}",
                productName,
                expectedPrice,
                candidates.size(),
                matchedCandidates.size(),
                filterResult.priceMatchedCount(),
                filterResult.specMatchedCount(),
                candidatePrices(candidates),
                filterResult.mismatchReasons(),
                searchRoute(candidates, matchedCandidates, updates),
                updates.get(CartGraphStateKeys.WORKFLOW_STATUS),
                updates.get(CartGraphStateKeys.NEED_USER_INPUT));
        return updates;
    }

    private String routeAfterSearchCatalog(OverAllState state) {
        List<?> candidates = state.value(CartGraphStateKeys.PRODUCT_CANDIDATES, List.of());
        String workflowStatus = state.value(CartGraphStateKeys.WORKFLOW_STATUS, "");
        String route;
        if (CartWorkflowStatus.PRODUCT_CONSTRAINT_NOT_MATCHED.name().equals(workflowStatus)) {
            route = "NO_CANDIDATES";
        } else if (candidates.isEmpty()) {
            route = "NO_CANDIDATES";
        } else if (candidates.size() > 1) {
            route = "MULTI_CANDIDATES";
        } else {
            route = "ONE_CANDIDATE";
        }
        log.info("Cart route after search catalog: candidates={}, route={}", candidates.size(), route);
        return route;
    }

    CartCandidateFilterResult filterCandidatesByConstraints(
            List<ProductCandidate> candidates,
            CartCandidateConstraints constraints
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return new CartCandidateFilterResult(List.of(), List.of(), 0, 0);
        }
        if (constraints == null || !constraints.hasAny()) {
            return new CartCandidateFilterResult(List.copyOf(candidates), List.of(), candidates.size(), candidates.size());
        }
        List<ProductCandidate> matched = new ArrayList<>();
        Set<String> mismatchReasons = new LinkedHashSet<>();
        int priceMatchedCount = 0;
        int specMatchedCount = 0;
        for (ProductCandidate candidate : candidates) {
            boolean priceMatched = priceMatches(candidate, constraints.expectedPrice());
            boolean productIdentityMatched = productIdentityMatches(candidate, constraints);
            boolean colorMatched = colorMatches(candidate, constraints.colorTokens());
            boolean sizeMatched = sizeMatches(candidate, constraints.sizeTokens());
            boolean specMatched = specMatches(candidate, constraints.specTokens());

            if (priceMatched) {
                priceMatchedCount++;
            } else if (constraints.expectedPrice() != null) {
                mismatchReasons.add("price_not_matched");
            }
            if (colorMatched && sizeMatched && specMatched) {
                specMatchedCount++;
            } else {
                if (!productIdentityMatched) {
                    mismatchReasons.add("product_identity_not_matched");
                }
                if (!colorMatched) {
                    mismatchReasons.add("color_not_matched");
                }
                if (!sizeMatched) {
                    mismatchReasons.add("size_not_matched");
                }
                if (!specMatched) {
                    mismatchReasons.add("spec_not_matched");
                }
            }
            if (priceMatched && productIdentityMatched && colorMatched && sizeMatched && specMatched) {
                matched.add(candidate);
            }
        }
        return new CartCandidateFilterResult(List.copyOf(matched), List.copyOf(mismatchReasons),
                priceMatchedCount, specMatchedCount);
    }

    private boolean priceMatches(ProductCandidate candidate, BigDecimal expectedPrice) {
        if (expectedPrice == null) {
            return true;
        }
        return candidate != null && candidate.price() != null
                && candidate.price().compareTo(expectedPrice) == 0;
    }

    private boolean colorMatches(ProductCandidate candidate, List<String> colorTokens) {
        if (colorTokens == null || colorTokens.isEmpty()) {
            return true;
        }
        String text = normalizeMatchText(String.join(" ",
                nullToEmpty(candidate == null ? null : candidate.spec()),
                nullToEmpty(candidate == null ? null : candidate.brief())
        ));
        return colorTokens.stream().anyMatch(text::contains);
    }

    private boolean sizeMatches(ProductCandidate candidate, List<String> sizeTokens) {
        if (sizeTokens == null || sizeTokens.isEmpty()) {
            return true;
        }
        String text = normalizeMatchText(String.join(" ",
                nullToEmpty(candidate == null ? null : candidate.productName()),
                nullToEmpty(candidate == null ? null : candidate.spec()),
                nullToEmpty(candidate == null ? null : candidate.brief())
        ));
        return sizeTokens.stream().allMatch(text::contains);
    }

    private boolean specMatches(ProductCandidate candidate, List<String> specTokens) {
        if (specTokens == null || specTokens.isEmpty()) {
            return true;
        }
        String text = normalizeMatchText(String.join(" ",
                nullToEmpty(candidate == null ? null : candidate.spec()),
                nullToEmpty(candidate == null ? null : candidate.brief())
        ));
        return specTokens.stream().allMatch(text::contains);
    }

    private boolean productIdentityMatches(ProductCandidate candidate, CartCandidateConstraints constraints) {
        if (constraints == null) {
            return true;
        }
        if (StringUtils.hasText(constraints.productId())) {
            return candidate != null && constraints.productId().equals(candidate.productId());
        }
        if (StringUtils.hasText(constraints.skuId())) {
            return candidate != null && constraints.skuId().equals(candidate.skuId());
        }
        if (StringUtils.hasText(constraints.productName())) {
            String candidateName = normalizeMatchText(candidate == null ? null : candidate.productName());
            String expectedName = normalizeMatchText(constraints.productName());
            return !StringUtils.hasText(expectedName)
                    || candidateName.contains(expectedName)
                    || expectedName.contains(candidateName);
        }
        return true;
    }

    private String constraintMismatchMessage(
            String productName,
            CartCandidateConstraints constraints,
            List<ProductCandidate> candidates
    ) {
        String displayName = StringUtils.hasText(productName) ? "「" + productName + "」" : "该商品";
        String prefix = "找到了类似商品，但没有满足你指定条件的商品。";
        if (constraints != null && constraints.expectedPrice() != null) {
            return prefix + "你要求价格为 " + formatPrice(constraints.expectedPrice()) + " 的" + displayName
                    + "，但当前可选候选价格为 " + formatPriceList(candidates)
                    + "，价格不匹配。请确认是否换一个价格或关键词。";
        }
        if (constraints != null && constraints.hasColor()) {
            return prefix + "你要求颜色为 " + String.join("/", constraints.colorTokens())
                    + "，但当前候选颜色不匹配。请确认是否换一个颜色或关键词。";
        }
        if (constraints != null && constraints.hasSize()) {
            return prefix + "你要求规格为 " + String.join("/", constraints.sizeTokens())
                    + "，但当前候选规格不匹配。请确认是否换一个规格或关键词。";
        }
        return prefix + "请确认商品名称、规格或价格后重新发送。";
    }

    private String searchRoute(
            List<ProductCandidate> originalCandidates,
            List<ProductCandidate> matchedCandidates,
            Map<String, Object> updates
    ) {
        if (originalCandidates == null || originalCandidates.isEmpty()) {
            return "NOT_FOUND";
        }
        if (CartWorkflowStatus.PRODUCT_CONSTRAINT_NOT_MATCHED.name()
                .equals(updates.get(CartGraphStateKeys.WORKFLOW_STATUS))) {
            return "CONSTRAINT_NOT_MATCHED";
        }
        if (matchedCandidates.size() == 1) {
            return "ONE_MATCH";
        }
        return "MULTI_MATCH";
    }

    private List<String> candidatePrices(List<ProductCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .map(ProductCandidate::price)
                .filter(Objects::nonNull)
                .map(this::formatPrice)
                .distinct()
                .toList();
    }

    private String formatPriceList(List<ProductCandidate> candidates) {
        List<String> prices = candidatePrices(candidates);
        if (prices.isEmpty()) {
            return "未知";
        }
        if (prices.size() == 1) {
            return prices.getFirst();
        }
        return String.join(" 和 ", prices);
    }

    private String formatPrice(BigDecimal price) {
        if (price == null) {
            return "未知";
        }
        return "¥" + price.stripTrailingZeros().toPlainString();
    }

    private Map<String, Object> cartResolveCandidate(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        String userMessage = state.value(GuideGraphStateKeys.MESSAGE, "");
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);

        log.info("Cart resolve candidate start: userId={}, conversationId={}, messageLength={}",
                userId, conversationId, userMessage == null ? 0 : userMessage.length());
        Optional<PendingCartActionRecord> pendingOpt = pendingCartActionRepository
                .findActiveByUserIdAndConversationId(userId, conversationId);

        if (pendingOpt.isEmpty()) {
            updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.FAILED.name());
            updates.put(CartGraphStateKeys.NODE_MESSAGE, "没有找到待选择的商品，请重新发起加购请求。");
            log.info("Cart resolve candidate failed: reason=no_active_pending");
            return updates;
        }

        PendingCartActionRecord pending = pendingOpt.get();
        CandidateSelectionResult selection = resolveCandidateSelection(userMessage, pending.candidates());
        int selectedIndex = selection.selectedIndex();
        log.info("Cart resolve candidate parsed selection: pendingId={}, candidates={}, status={}, selectedIndex={}",
                pending.id(), pending.candidates().size(), selection.status(), selectedIndex);

        if (selection.status() != CandidateSelectionStatus.SELECTED
                || selectedIndex < 1
                || selectedIndex > pending.candidates().size()) {
            updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.WAITING_CLARIFICATION.name());
            updates.put(CartGraphStateKeys.NODE_MESSAGE,
                    "请回复 1-" + pending.candidates().size() + " 之间的序号，例如“选第 1 个”。");
            updates.put(CartGraphStateKeys.NEED_USER_INPUT, true);
            log.info("Cart resolve candidate waiting clarification: pendingId={}, candidates={}, status={}, selectedIndex={}",
                    pending.id(), pending.candidates().size(), selection.status(), selectedIndex);
            return updates;
        }

        ProductCandidate selected = pending.candidates().get(selectedIndex - 1);
        updates.put(CartGraphStateKeys.PRODUCT_ID, selected.productId());
        updates.put(CartGraphStateKeys.SKU_ID, selected.skuId());
        updates.put(CartGraphStateKeys.QUANTITY, pending.quantity() == null ? 1 : pending.quantity());
        updates.put(CartGraphStateKeys.SELECTED_CANDIDATE, selected);
        updates.put(CartGraphStateKeys.PRODUCT_NAME, selected.productName());
        updates.put(CartGraphStateKeys.CART_STATUS, "PRODUCT_SELECTED");

        pendingCartActionRepository.markCompleted(pending.id());
        log.info("Cart resolve candidate done: pendingId={}, selectedIndex={}, productId={}, skuId={}",
                pending.id(), selectedIndex, selected.productId(), selected.skuId());
        return updates;
    }

    private String routeAfterResolveCandidate(OverAllState state) {
        String productId = state.value(CartGraphStateKeys.PRODUCT_ID, "");
        String skuId = state.value(CartGraphStateKeys.SKU_ID, "");
        if (StringUtils.hasText(productId) && StringUtils.hasText(skuId)) {
            log.info("Cart route after resolve candidate: productId={}, skuId={}, route=HAS_IDS", productId, skuId);
            return "HAS_IDS";
        }
        log.info("Cart route after resolve candidate: productId={}, skuId={}, route=FINAL", productId, skuId);
        return "FINAL";
    }

    private Map<String, Object> cartCheckStock(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        String productId = state.value(CartGraphStateKeys.PRODUCT_ID, "");
        String skuId = state.value(CartGraphStateKeys.SKU_ID, "");
        int quantity = state.value(CartGraphStateKeys.QUANTITY, 1);

        log.info("Cart check stock start: productId={}, skuId={}, quantity={}", productId, skuId, quantity);
        StockResult stock = inventoryQueryService.checkStock(productId, skuId, quantity);
        updates.put(CartGraphStateKeys.STOCK_RESULT, stock);

        if (!stock.available()) {
            String message = String.format(Locale.ROOT, "「%s」库存不足，当前最多可购买 %d 件。",
                    state.value(CartGraphStateKeys.PRODUCT_NAME, "该商品"),
                    Math.max(stock.availableQty(), 0));
            updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.STOCK_NOT_ENOUGH.name());
            updates.put(CartGraphStateKeys.NODE_MESSAGE, message);
            updates.put(CartGraphStateKeys.NEED_USER_INPUT, false);
        }

        log.info("Cart check stock done: productId={}, skuId={}, quantity={}, available={}, availableQty={}, status={}",
                productId,
                skuId,
                quantity,
                stock.available(),
                stock.availableQty(),
                updates.get(CartGraphStateKeys.WORKFLOW_STATUS));
        return updates;
    }

    private String routeAfterCheckStock(OverAllState state) {
        Optional<StockResult> stock = state.value(CartGraphStateKeys.STOCK_RESULT, StockResult.class);
        String route;
        if (stock.isEmpty() || !stock.get().available()) {
            route = "OUT_OF_STOCK";
        } else {
            route = "IN_STOCK";
        }
        log.info("Cart route after check stock: hasStockResult={}, available={}, route={}",
                stock.isPresent(), stock.map(StockResult::available).orElse(false), route);
        return route;
    }

    private Map<String, Object> cartExecuteAction(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        CartAction action = safeCartAction(
                state.value(CartGraphStateKeys.CART_ACTION, CartAction.UNKNOWN.name())
        );

        CartView cart = cartQueryService.getUserCart(userId, conversationId);
        log.info("Cart execute action start: userId={}, conversationId={}, action={}, cartItems={}",
                userId, conversationId, action, cart == null || cart.items() == null ? 0 : cart.items().size());

        switch (action) {
            case VIEW -> {
                updates.put(CartGraphStateKeys.CART_RESULT, cart);
                updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.VIEW_SUCCESS.name());
                updates.put(CartGraphStateKeys.NODE_MESSAGE, formatCartViewMessage(cart));
            }
            case CLEAR -> {
                var result = cartCommandService.clearCart(userId, conversationId);
                updates.put(CartGraphStateKeys.CART_RESULT, result);
                if (putMutationFailure(updates, result)) {
                    break;
                }
                updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.CLEAR_SUCCESS.name());
                updates.put(CartGraphStateKeys.NODE_MESSAGE, "购物车已清空。");
            }
            case ADD, CONFIRM -> {
                String productId = state.value(CartGraphStateKeys.PRODUCT_ID, "");
                String skuId = state.value(CartGraphStateKeys.SKU_ID, "");
                int quantity = state.value(CartGraphStateKeys.QUANTITY, 1);
                BigDecimal expectedPrice = state.value(CartGraphStateKeys.EXPECTED_PRICE, BigDecimal.class)
                        .orElse(null);
                var mutation = cartCommandService.addItem(userId, conversationId, productId, skuId, quantity, expectedPrice);
                updates.put(CartGraphStateKeys.CART_RESULT, mutation);
                if (putMutationFailure(updates, mutation)) {
                    break;
                }
                updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.ADD_SUCCESS.name());
                String productName = state.value(CartGraphStateKeys.PRODUCT_NAME, "该商品");
                updates.put(CartGraphStateKeys.NODE_MESSAGE, "已将「" + productName + "」加入购物车，数量 " + quantity + "。");
            }
            case REMOVE -> {
                Integer itemIndex = state.<Integer>value(CartGraphStateKeys.ITEM_INDEX).orElse(null);
                String productName = state.value(CartGraphStateKeys.PRODUCT_NAME, "");
                String productId = state.value(CartGraphStateKeys.PRODUCT_ID, "");
                String skuId = state.value(CartGraphStateKeys.SKU_ID, "");
                CartItemView target = findCartItem(cart, itemIndex, productName, productId, skuId);
                log.info("Cart execute remove target resolved: itemIndex={}, productName={}, productId={}, skuId={}, targetItemId={}",
                        itemIndex, productName, productId, skuId, target == null ? null : target.itemId());
                if (target != null) {
                    var mutation = cartCommandService.removeItem(userId, conversationId, String.valueOf(target.itemId()));
                    updates.put(CartGraphStateKeys.CART_RESULT, mutation);
                    if (putMutationFailure(updates, mutation)) {
                        break;
                    }
                    updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.REMOVE_SUCCESS.name());
                    updates.put(CartGraphStateKeys.NODE_MESSAGE, "已从购物车删除该商品。");
                } else {
                    updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.ITEM_NOT_FOUND.name());
                    updates.put(CartGraphStateKeys.NODE_MESSAGE, "购物车里没有找到该商品，请换个说法，或先查看购物车。");
                }
            }
            case UPDATE_QUANTITY -> {
                Integer itemIndex = state.<Integer>value(CartGraphStateKeys.ITEM_INDEX).orElse(null);
                Integer quantity = state.value(CartGraphStateKeys.QUANTITY, 1);
                String productName = state.value(CartGraphStateKeys.PRODUCT_NAME, "");
                String productId = state.value(CartGraphStateKeys.PRODUCT_ID, "");
                String skuId = state.value(CartGraphStateKeys.SKU_ID, "");
                CartItemView target = findCartItem(cart, itemIndex, productName, productId, skuId);
                log.info("Cart execute update target resolved: itemIndex={}, productName={}, productId={}, skuId={}, quantity={}, targetItemId={}",
                        itemIndex, productName, productId, skuId, quantity, target == null ? null : target.itemId());
                if (target != null) {
                    var mutation = cartCommandService.updateQuantity(userId, conversationId, String.valueOf(target.itemId()), quantity);
                    updates.put(CartGraphStateKeys.CART_RESULT, mutation);
                    if (putMutationFailure(updates, mutation)) {
                        break;
                    }
                    updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.UPDATE_SUCCESS.name());
                    updates.put(CartGraphStateKeys.NODE_MESSAGE, "已更新购物车商品数量。");
                } else {
                    updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.ITEM_NOT_FOUND.name());
                    updates.put(CartGraphStateKeys.NODE_MESSAGE, "购物车里没有找到该商品，请换个说法，或先查看购物车。");
                }
            }
            default -> {
                updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.FAILED.name());
                updates.put(CartGraphStateKeys.NODE_MESSAGE, "我没太理解你的购物车操作。");
            }
        }

        log.info("Executed cart action: {}, status: {}", action,
                updates.get(CartGraphStateKeys.WORKFLOW_STATUS));
        return updates;
    }

    private boolean putMutationFailure(Map<String, Object> updates, CartMutationResult mutation) {
        if (mutation == null || mutation.success()) {
            return false;
        }
        updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.FAILED.name());
        updates.put(CartGraphStateKeys.NEED_USER_INPUT, false);
        updates.put(CartGraphStateKeys.NODE_MESSAGE, mutationFailureMessage(mutation));
        return true;
    }

    private String mutationFailureMessage(CartMutationResult mutation) {
        String message = mutation.errorMessage();
        if (message == null || message.isBlank()) {
            return "购物车操作失败，请稍后重试。";
        }
        if (message.contains("价格已变化") || message.contains("价格发生变化")) {
            return "该商品价格发生变化，暂时不能直接加入购物车，请重新确认商品后再添加。";
        }
        return message;
    }

    private Map<String, Object> cartFinalResponse(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        log.info("Cart final response start: existingStatus={}, existingNeedUserInput={}, hasMessage={}",
                state.value(CartGraphStateKeys.WORKFLOW_STATUS).map(Object::toString).orElse(null),
                state.value(CartGraphStateKeys.NEED_USER_INPUT).orElse(null),
                state.value(CartGraphStateKeys.NODE_MESSAGE).isPresent());

        if (!state.value(CartGraphStateKeys.WORKFLOW_STATUS).isPresent()) {
            CartAction action = safeCartAction(
                    state.value(CartGraphStateKeys.CART_ACTION, CartAction.UNKNOWN.name())
            );
            updates.put(CartGraphStateKeys.WORKFLOW_STATUS,
                    switch (action) {
                        case ADD, REMOVE, UPDATE_QUANTITY -> CartWorkflowStatus.WAITING_CLARIFICATION.name();
                        default -> CartWorkflowStatus.FAILED.name();
                    });
        }
        if (!state.value(CartGraphStateKeys.NODE_MESSAGE).isPresent()) {
            updates.put(CartGraphStateKeys.NODE_MESSAGE, fallbackCartMessage(state));
        }
        if (!state.value(CartGraphStateKeys.NEED_USER_INPUT).isPresent()) {
            String status = state.value(CartGraphStateKeys.WORKFLOW_STATUS)
                    .map(Object::toString)
                    .orElseGet(() -> String.valueOf(updates.get(CartGraphStateKeys.WORKFLOW_STATUS)));
            updates.put(CartGraphStateKeys.NEED_USER_INPUT,
                    CartWorkflowStatus.WAITING_CLARIFICATION.name().equals(status)
                            || CartWorkflowStatus.WAITING_USER_SELECTION.name().equals(status));
        }

        log.info("Cart final response done: status={}, needUserInput={}, messageSource={}",
                state.value(CartGraphStateKeys.WORKFLOW_STATUS)
                        .map(Object::toString)
                        .orElseGet(() -> String.valueOf(updates.get(CartGraphStateKeys.WORKFLOW_STATUS))),
                state.value(CartGraphStateKeys.NEED_USER_INPUT)
                        .map(Object::toString)
                        .orElseGet(() -> String.valueOf(updates.get(CartGraphStateKeys.NEED_USER_INPUT))),
                state.value(CartGraphStateKeys.NODE_MESSAGE).isPresent() ? "existing" : "fallback");
        return updates;
    }

    private String fallbackCartMessage(OverAllState state) {
        CartAction action = safeCartAction(
                state.value(CartGraphStateKeys.CART_ACTION, CartAction.UNKNOWN.name())
        );
        return switch (action) {
            case REMOVE, UPDATE_QUANTITY -> "请说明要操作购物车中的第几个商品，例如“删除第 1 个”或“把第 2 个改成 3 件”。";
            default -> "我还缺少必要信息，无法完成这次购物车操作。";
        };
    }

    private String formatCandidateQuestion(List<ProductCandidate> candidates) {
        StringBuilder builder = new StringBuilder("我找到几款可能符合的商品，请选择要加入购物车的商品：");
        for (int i = 0; i < candidates.size(); i++) {
            ProductCandidate candidate = candidates.get(i);
            builder.append('\n')
                    .append(i + 1)
                    .append(". ")
                    .append(candidate.productName() == null ? "未命名商品" : candidate.productName());
            if (candidate.price() != null) {
                builder.append(" - ¥").append(candidate.price());
            }
            if (StringUtils.hasText(candidate.spec())) {
                builder.append(" - ").append(candidate.spec());
            } else if (StringUtils.hasText(candidate.brief())) {
                builder.append(" - ").append(candidate.brief());
            }
        }
        builder.append("\n请回复“选第 1 个”或对应序号。");
        return builder.toString();
    }

    private String formatCartViewMessage(CartView cart) {
        if (cart == null || cart.items() == null || cart.items().isEmpty()) {
            return "你的购物车目前是空的。";
        }
        StringBuilder sb = new StringBuilder("你的购物车商品列表：\n");
        for (int i = 0; i < cart.items().size(); i++) {
            CartItemView item = cart.items().get(i);
            sb.append(i + 1).append(". ").append(item.title()).append(" x").append(item.quantity()).append("\n");
        }
        return sb.toString().trim();
    }

    CandidateSelectionResult resolveCandidateSelection(String message, List<ProductCandidate> candidates) {
        int candidateCount = candidates == null ? 0 : candidates.size();
        int index = parseSelectionIndex(message, candidateCount);
        if (isValidCandidateIndex(index, candidateCount)) {
            return CandidateSelectionResult.selected(index);
        }

        CandidateSelectionResult attributeSelection = attributeMatch(message, candidates);
        if (attributeSelection.status() != CandidateSelectionStatus.UNMATCHED) {
            return attributeSelection;
        }

        if (candidateSelectionLlmService == null) {
            return CandidateSelectionResult.unmatched();
        }
        Optional<Integer> llmIndex = candidateSelectionLlmService.resolveIndex(message, candidates);
        if (llmIndex.isPresent() && isValidCandidateIndex(llmIndex.get(), candidateCount)) {
            return CandidateSelectionResult.selected(llmIndex.get());
        }
        return CandidateSelectionResult.unmatched();
    }

    CandidateSelectionResult attributeMatch(String message, List<ProductCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return CandidateSelectionResult.unmatched();
        }
        List<String> tokens = candidateSelectionTokens(message);
        if (tokens.isEmpty()) {
            return CandidateSelectionResult.unmatched();
        }
        List<Integer> matchedIndexes = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            String candidateText = normalizedCandidateText(candidates.get(i));
            if (tokens.stream().anyMatch(candidateText::contains)) {
                matchedIndexes.add(i + 1);
            }
        }
        if (matchedIndexes.size() == 1) {
            return CandidateSelectionResult.selected(matchedIndexes.getFirst());
        }
        if (matchedIndexes.size() > 1) {
            return CandidateSelectionResult.ambiguous();
        }
        return CandidateSelectionResult.unmatched();
    }

    int parseSelectionIndex(String message, int candidateCount) {
        if (isImplicitThisSelection(message)) {
            return candidateCount == 1 ? 1 : -1;
        }
        if (message == null) {
            return -1;
        }
        Matcher matcher = SELECTION_PATTERN.matcher(message.trim());
        if (matcher.matches()) {
            Integer index = parseOneToFive(matcher.group(1));
            if (index != null) {
                return index;
            }
        }
        return -1;
    }

    private boolean isImplicitThisSelection(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.trim();
        return "就这个".equals(normalized)
                || "就要这个".equals(normalized)
                || "要这个".equals(normalized)
                || "这个".equals(normalized);
    }

    private Integer parseOneToFive(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return switch (value.trim()) {
            case "1", "一" -> 1;
            case "2", "二" -> 2;
            case "3", "三" -> 3;
            case "4", "四" -> 4;
            case "5", "五" -> 5;
            default -> null;
        };
    }

    private boolean isValidCandidateIndex(int index, int candidateCount) {
        return index >= 1 && index <= candidateCount;
    }

    private List<String> candidateSelectionTokens(String message) {
        String normalized = normalizeMatchText(message);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        String cleaned = normalized
                .replace("我想要", "")
                .replace("我要", "")
                .replace("我选择", "")
                .replace("我选", "")
                .replace("选择", "")
                .replace("选", "")
                .replace("就要", "")
                .replace("要", "")
                .replace("那个", "")
                .replace("这个", "")
                .replace("这款", "")
                .replace("的", "")
                .replace("款", "")
                .replace("号", "");
        List<String> tokens = new ArrayList<>();
        addSelectionToken(tokens, cleaned);
        if (cleaned.endsWith("色") && cleaned.length() > 1) {
            addSelectionToken(tokens, cleaned.substring(0, cleaned.length() - 1));
        }
        return tokens;
    }

    private void addSelectionToken(List<String> tokens, String token) {
        if (!StringUtils.hasText(token)) {
            return;
        }
        String trimmed = token.trim();
        if (trimmed.length() >= 2 && !"一个".equals(trimmed) && !"第二".equals(trimmed)) {
            tokens.add(trimmed);
        }
    }

    private boolean hasCartItemTarget(Integer itemIndex, String productName, String productId, String skuId) {
        return itemIndex != null && itemIndex >= 1
                || StringUtils.hasText(productName)
                || StringUtils.hasText(productId)
                || StringUtils.hasText(skuId);
    }

    private CartItemView findCartItem(
            CartView cart,
            Integer itemIndex,
            String productName,
            String productId,
            String skuId
    ) {
        if (cart == null || cart.items() == null || cart.items().isEmpty()) {
            return null;
        }
        List<CartItemView> items = cart.items();
        if (itemIndex != null && itemIndex >= 1 && itemIndex <= items.size()) {
            return items.get(itemIndex - 1);
        }
        if (StringUtils.hasText(productId) && StringUtils.hasText(skuId)) {
            Optional<CartItemView> matched = items.stream()
                    .filter(item -> matchesProductId(item, productId) && matchesSkuId(item, skuId))
                    .findFirst();
            if (matched.isPresent()) {
                return matched.get();
            }
        }
        if (StringUtils.hasText(skuId)) {
            Optional<CartItemView> matched = items.stream()
                    .filter(item -> matchesSkuId(item, skuId))
                    .findFirst();
            if (matched.isPresent()) {
                return matched.get();
            }
        }
        if (StringUtils.hasText(productId)) {
            Optional<CartItemView> matched = items.stream()
                    .filter(item -> matchesProductId(item, productId))
                    .findFirst();
            if (matched.isPresent()) {
                return matched.get();
            }
        }
        if (StringUtils.hasText(productName)) {
            String normalizedName = normalizeMatchText(productName);
            return items.stream()
                    .filter(item -> normalizeMatchText(item.title()).contains(normalizedName))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private boolean matchesProductId(CartItemView item, String productId) {
        if (!StringUtils.hasText(productId) || item == null) {
            return false;
        }
        String normalized = productId.trim();
        return item.spuId() != null && normalized.equals(String.valueOf(item.spuId()))
                || StringUtils.hasText(item.externalRef()) && item.externalRef().contains(normalized);
    }

    private boolean matchesSkuId(CartItemView item, String skuId) {
        if (!StringUtils.hasText(skuId) || item == null) {
            return false;
        }
        return StringUtils.hasText(item.externalRef()) && item.externalRef().contains(skuId.trim());
    }

    private String normalizeMatchText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String normalizedCandidateText(ProductCandidate candidate) {
        if (candidate == null) {
            return "";
        }
        return normalizeMatchText(String.join(" ",
                nullToEmpty(candidate.productName()),
                nullToEmpty(candidate.spec()),
                nullToEmpty(candidate.brief()),
                nullToEmpty(candidate.externalRef()),
                nullToEmpty(candidate.productId()),
                nullToEmpty(candidate.skuId())
        ));
    }

    private boolean looksLikeNewCartRequest(String message) {
        CartAction action = inferActionFromMessage(message);
        return action == CartAction.ADD
                || action == CartAction.REMOVE
                || action == CartAction.UPDATE_QUANTITY
                || action == CartAction.VIEW
                || action == CartAction.CLEAR;
    }

    private CartAction safeCartAction(String value) {
        if (!StringUtils.hasText(value)) {
            return CartAction.UNKNOWN;
        }
        try {
            return CartAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return CartAction.UNKNOWN;
        }
    }

    private CartAction parseCartAction(String value) {
        if (value == null) return CartAction.UNKNOWN;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "ADD", "ADD_TO_CART" -> CartAction.ADD;
            case "REMOVE", "REMOVE_ITEM", "REMOVE_FROM_CART" -> CartAction.REMOVE;
            case "UPDATE_QUANTITY", "UPDATE", "UPDATE_CART_ITEM" -> CartAction.UPDATE_QUANTITY;
            case "VIEW", "VIEW_CART" -> CartAction.VIEW;
            case "CLEAR", "CLEAR_CART" -> CartAction.CLEAR;
            case "CONFIRM" -> CartAction.CONFIRM;
            case "CANCEL" -> CartAction.CANCEL;
            default -> CartAction.UNKNOWN;
        };
    }

    private CartAction inferActionFromMessage(String message) {
        if (message == null) {
            return CartAction.UNKNOWN;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("加") || lower.contains("买") || lower.contains("加入")) return CartAction.ADD;
        if (lower.contains("删") || lower.contains("移除")) return CartAction.REMOVE;
        if (lower.contains("改") || lower.contains("更新") || lower.contains("数量")) return CartAction.UPDATE_QUANTITY;
        if (lower.contains("看") || lower.contains("查看") || lower.contains("我的购物车")) return CartAction.VIEW;
        if (lower.contains("清空") || lower.contains("清空购物车") || lower.contains("清掉购物车")) return CartAction.CLEAR;
        return CartAction.UNKNOWN;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readIntentSlots(OverAllState state) {
        return state.value(GuideGraphStateKeys.INTENT_SLOTS)
                .filter(v -> v instanceof Map)
                .map(v -> (Map<String, Object>) v)
                .orElse(Map.of());
    }

    private String conversationMemory(OverAllState state) {
        List<?> recentMessages = state.value(GuideGraphStateKeys.RECENT_MESSAGES, List.of());
        if (recentMessages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Object value : recentMessages) {
            if (value instanceof ConversationMessage message) {
                builder.append(message.role()).append(": ").append(message.content()).append('\n');
            }
        }
        return builder.toString().trim();
    }

    private String requiredString(OverAllState state, String key) {
        return state.value(key)
                .map(Object::toString)
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Missing state key: " + key));
    }

    private static String asString(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Integer asInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static BigDecimal extractExpectedPrice(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        for (Pattern pattern : PRICE_PATTERNS) {
            Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {
                try {
                    return new BigDecimal(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (StringUtils.hasText(v)) return v;
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) return null;
        for (T value : values) {
            if (value != null) return value;
        }
        return null;
    }

    record CartCandidateConstraints(
            String productName,
            String productId,
            String skuId,
            Integer quantity,
            BigDecimal expectedPrice,
            List<String> colorTokens,
            List<String> sizeTokens,
            List<String> specTokens
    ) {
        CartCandidateConstraints {
            colorTokens = colorTokens == null ? List.of() : List.copyOf(colorTokens);
            sizeTokens = sizeTokens == null ? List.of() : List.copyOf(sizeTokens);
            specTokens = specTokens == null ? List.of() : List.copyOf(specTokens);
        }

        static CartCandidateConstraints from(
                String userMessage,
                String productName,
                String productId,
                String skuId,
                Integer quantity,
                BigDecimal expectedPrice
        ) {
            String text = String.join(" ", nullToEmpty(userMessage), nullToEmpty(productName));
            return new CartCandidateConstraints(
                    blankToNullStatic(productName),
                    blankToNullStatic(productId),
                    blankToNullStatic(skuId),
                    quantity,
                    expectedPrice,
                    extractColorTokens(text),
                    extractSizeTokens(text),
                    List.of()
            );
        }

        boolean hasAny() {
            return StringUtils.hasText(productName)
                    || StringUtils.hasText(productId)
                    || StringUtils.hasText(skuId)
                    || quantity != null
                    || expectedPrice != null
                    || hasColor()
                    || hasSize()
                    || !specTokens.isEmpty();
        }

        boolean hasColor() {
            return !colorTokens.isEmpty();
        }

        boolean hasSize() {
            return !sizeTokens.isEmpty();
        }

        private static List<String> extractColorTokens(String text) {
            String normalized = normalizeConstraintText(text);
            if (!StringUtils.hasText(normalized)) {
                return List.of();
            }
            LinkedHashSet<String> colors = new LinkedHashSet<>();
            for (String color : COLOR_WORDS) {
                if (normalized.contains(color)) {
                    colors.add(color);
                    if (color.endsWith("色") && color.length() > 1) {
                        colors.add(color.substring(0, color.length() - 1));
                    }
                }
            }
            return List.copyOf(colors);
        }

        private static List<String> extractSizeTokens(String text) {
            if (!StringUtils.hasText(text)) {
                return List.of();
            }
            Matcher matcher = SIZE_PATTERN.matcher(text);
            LinkedHashSet<String> sizes = new LinkedHashSet<>();
            while (matcher.find()) {
                sizes.add(matcher.group(1) + "寸");
            }
            return List.copyOf(sizes);
        }

        private static String normalizeConstraintText(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        }

        private static String blankToNullStatic(String value) {
            return StringUtils.hasText(value) ? value : null;
        }
    }

    record CartCandidateFilterResult(
            List<ProductCandidate> matchedCandidates,
            List<String> mismatchReasons,
            int priceMatchedCount,
            int specMatchedCount
    ) {
    }

    enum CandidateSelectionStatus {
        SELECTED,
        AMBIGUOUS,
        UNMATCHED
    }

    record CandidateSelectionResult(CandidateSelectionStatus status, int selectedIndex) {
        static CandidateSelectionResult selected(int selectedIndex) {
            return new CandidateSelectionResult(CandidateSelectionStatus.SELECTED, selectedIndex);
        }

        static CandidateSelectionResult ambiguous() {
            return new CandidateSelectionResult(CandidateSelectionStatus.AMBIGUOUS, -1);
        }

        static CandidateSelectionResult unmatched() {
            return new CandidateSelectionResult(CandidateSelectionStatus.UNMATCHED, -1);
        }
    }
}
