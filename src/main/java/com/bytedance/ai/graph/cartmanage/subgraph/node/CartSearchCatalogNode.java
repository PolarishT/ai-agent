package com.bytedance.ai.graph.cartmanage.subgraph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import com.bytedance.ai.graph.cartmanage.application.ProductCatalogResolver;
import com.bytedance.ai.graph.conversation.context.ConversationContextManager;
import com.bytedance.ai.graph.conversation.context.ConversationRuntimeContext;
import com.bytedance.ai.graph.cartmanage.subgraph.CartAction;
import com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys;
import com.bytedance.ai.graph.cartmanage.subgraph.CartWorkflowStatus;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartCandidateMatcher;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartCandidateMatcher.CartCandidateConstraints;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartCandidateMatcher.CartCandidateFilterResult;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartGraphStateSupport;
import com.bytedance.ai.graph.orchestration.GuideGraphStateKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 购物车管理子图节点，根据用户目标商品描述检索目录候选商品。
 */
public class CartSearchCatalogNode {

    private static final Logger log = LoggerFactory.getLogger(CartSearchCatalogNode.class);

    private final ProductCatalogResolver productCatalogResolver;
    private final ConversationContextManager conversationContextManager;
    private final CartCandidateMatcher candidateMatcher;

    public CartSearchCatalogNode(
            ProductCatalogResolver productCatalogResolver,
            ConversationContextManager conversationContextManager,
            CartCandidateMatcher candidateMatcher
    ) {
        this.productCatalogResolver = productCatalogResolver;
        this.conversationContextManager = conversationContextManager;
        this.candidateMatcher = candidateMatcher;
    }

    public Map<String, Object> apply(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        String productName = state.value(CartGraphStateKeys.PRODUCT_NAME, "");
        String userMessage = state.value(GuideGraphStateKeys.MESSAGE, "");
        int quantity = state.value(CartGraphStateKeys.QUANTITY, 1);
        BigDecimal expectedPrice = state.value(CartGraphStateKeys.EXPECTED_PRICE, BigDecimal.class)
                .orElseGet(() -> CartGraphStateSupport.extractExpectedPrice(userMessage));
        String userId = CartGraphStateSupport.requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = CartGraphStateSupport.requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);

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
        CartCandidateFilterResult filterResult = candidateMatcher.filter(candidates, constraints);
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
                    candidateMatcher.constraintMismatchMessage(productName, constraints, candidates));
            updates.put(CartGraphStateKeys.NEED_USER_INPUT, true);
        } else if (matchedCandidates.size() == 1) {
            ProductCandidate only = matchedCandidates.getFirst();
            updates.put(CartGraphStateKeys.PRODUCT_ID, only.productId());
            updates.put(CartGraphStateKeys.SKU_ID, only.skuId());
            updates.put(CartGraphStateKeys.PRODUCT_NAME, only.productName());
            updates.put(CartGraphStateKeys.SELECTED_CANDIDATE, only);
            updates.put(CartGraphStateKeys.CART_STATUS, "PRODUCT_SELECTED");
        } else {
            ConversationRuntimeContext.PendingClarification saved = saveCandidateClarification(
                    userId,
                    conversationId,
                    state.value(GuideGraphStateKeys.RUN_ID, ""),
                    quantity,
                    matchedCandidates
            );
            if (saved != null) {
                updates.put(CartGraphStateKeys.PENDING_CLARIFICATION_ID, saved.contextItemId());
            }
            updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.WAITING_USER_SELECTION.name());
            updates.put(CartGraphStateKeys.NODE_MESSAGE, formatCandidateQuestion(matchedCandidates));
            updates.put(CartGraphStateKeys.NEED_USER_INPUT, true);
            log.info("Cart search catalog pending created: pendingId={}, candidates={}",
                    saved == null ? null : saved.contextItemId(), matchedCandidates.size());
        }

        log.info("Cart search catalog done: productName={}, expectedPrice={}, originalCandidateCount={}, matchedCandidateCount={}, priceMatchedCount={}, specMatchedCount={}, candidatePrices={}, mismatchReasons={}, route={}, status={}, needUserInput={}",
                productName,
                expectedPrice,
                candidates.size(),
                matchedCandidates.size(),
                filterResult.priceMatchedCount(),
                filterResult.specMatchedCount(),
                candidateMatcher.candidatePrices(candidates),
                filterResult.mismatchReasons(),
                searchRoute(candidates, matchedCandidates, updates),
                updates.get(CartGraphStateKeys.WORKFLOW_STATUS),
                updates.get(CartGraphStateKeys.NEED_USER_INPUT));
        return updates;
    }

    private ConversationRuntimeContext.PendingClarification saveCandidateClarification(
            String userId,
            String conversationId,
            String turnId,
            int quantity,
            List<ProductCandidate> matchedCandidates
    ) {
        if (conversationContextManager == null) {
            return null;
        }
        List<ConversationRuntimeContext.ProductCandidateItem> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < matchedCandidates.size(); i++) {
            ProductCandidate candidate = matchedCandidates.get(i);
            candidates.add(new ConversationRuntimeContext.ProductCandidateItem(
                    null,
                    i + 1,
                    candidate.productId(),
                    candidate.skuId(),
                    candidate.productName(),
                    candidate.price(),
                    candidate.brief(),
                    candidate.spec(),
                    candidate.externalRef(),
                    null,
                    Map.of("source", "cart_fallback_search")
            ));
        }
        ConversationRuntimeContext.PendingClarification clarification =
                new ConversationRuntimeContext.PendingClarification(
                        null,
                        "CART_CANDIDATE_SELECTION",
                        "cart_manage_workflow",
                        quantity,
                        candidates,
                        LocalDateTime.now().plusHours(24),
                        Map.of("action", CartAction.ADD.name())
                );
        return conversationContextManager.savePendingClarification(
                userId,
                conversationId,
                turnId,
                "cart_manage_workflow",
                clarification,
                clarification.expiresAt()
        );
    }

    public String routeAfter(OverAllState state) {
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
}
