package com.bytedance.ai.graph.cartmanage.subgraph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.cartmanage.persistence.PendingCartActionRecord;
import com.bytedance.ai.graph.cartmanage.persistence.PendingCartActionRepository;
import com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CandidateSelectionResolver;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartActionParser;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartGraphStateSupport;
import com.bytedance.ai.graph.orchestration.GuideGraphStateKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class CartLoadContextNode {

    private static final Logger log = LoggerFactory.getLogger(CartLoadContextNode.class);

    private final PendingCartActionRepository pendingCartActionRepository;
    private final CandidateSelectionResolver candidateSelectionResolver;

    public CartLoadContextNode(
            PendingCartActionRepository pendingCartActionRepository,
            CandidateSelectionResolver candidateSelectionResolver
    ) {
        this.pendingCartActionRepository = pendingCartActionRepository;
        this.candidateSelectionResolver = candidateSelectionResolver;
    }

    public Map<String, Object> apply(OverAllState state) {
        String userId = CartGraphStateSupport.requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = CartGraphStateSupport.requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        String userMessage = state.value(GuideGraphStateKeys.MESSAGE, "");

        Map<String, Object> updates = new LinkedHashMap<>();
        clearTransientCartState(updates);
        log.info("Cart subgraph load context start: userId={}, conversationId={}, messageLength={}",
                userId, conversationId, userMessage == null ? 0 : userMessage.length());
        updates.put(GuideGraphStateKeys.USER_ID, userId);
        updates.put(GuideGraphStateKeys.CONVERSATION_ID, conversationId);
        updates.put(GuideGraphStateKeys.MESSAGE, userMessage);

        Optional<PendingCartActionRecord> pending = pendingCartActionRepository
                .findActiveByUserIdAndConversationId(userId, conversationId);
        boolean pendingLoaded = false;
        boolean stalePendingCancelled = false;
        if (pending.isPresent()) {
            if (candidateSelectionResolver.looksLikeCandidateSelection(userMessage, pending.get().candidates())
                    || !CartActionParser.looksLikeNewCartRequest(userMessage)) {
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

    private void clearTransientCartState(Map<String, Object> updates) {
        for (String key : java.util.List.of(
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
}
