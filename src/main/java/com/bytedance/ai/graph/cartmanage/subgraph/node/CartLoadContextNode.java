package com.bytedance.ai.graph.cartmanage.subgraph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.conversation.context.ConversationContextItemStatus;
import com.bytedance.ai.graph.conversation.context.ConversationContextManager;
import com.bytedance.ai.graph.conversation.context.ConversationRuntimeContext;
import com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CandidateSelectionResolver;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartActionParser;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartGraphStateSupport;
import com.bytedance.ai.graph.cartmanage.subgraph.support.ConversationProductCandidateMapper;
import com.bytedance.ai.graph.orchestration.GuideGraphStateKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 购物车管理子图节点，加载当前购物车、会话输入和后续节点所需上下文。
 */
public class CartLoadContextNode {

    private static final Logger log = LoggerFactory.getLogger(CartLoadContextNode.class);

    private final ConversationContextManager conversationContextManager;
    private final CandidateSelectionResolver candidateSelectionResolver;

    public CartLoadContextNode(
            ConversationContextManager conversationContextManager,
            CandidateSelectionResolver candidateSelectionResolver
    ) {
        this.conversationContextManager = conversationContextManager;
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

        ConversationRuntimeContext context = state.value(
                GuideGraphStateKeys.CONVERSATION_CONTEXT,
                ConversationRuntimeContext.class
        ).orElse(null);
        ConversationRuntimeContext.PendingClarification pending = context == null ? null : context.pendingClarification();
        boolean pendingLoaded = false;
        boolean stalePendingCancelled = false;
        if (pending != null && "CART_CANDIDATE_SELECTION".equals(pending.clarificationType())) {
            var candidates = ConversationProductCandidateMapper.toCartCandidates(pending.candidates());
            if (candidateSelectionResolver.looksLikeCandidateSelection(userMessage, candidates)
                    || !CartActionParser.looksLikeNewCartRequest(userMessage)) {
                updates.put(CartGraphStateKeys.PENDING_CLARIFICATION_ID, pending.contextItemId());
                updates.put(CartGraphStateKeys.PRODUCT_CANDIDATES, candidates);
                pendingLoaded = true;
                log.info("Loaded pending clarification {} for user {} conversation {} (selection follow-up)",
                        pending.contextItemId(), userId, conversationId);
            } else if (conversationContextManager != null) {
                conversationContextManager.markContextItemStatus(
                        pending.contextItemId(),
                        ConversationContextItemStatus.CANCELLED
                );
                stalePendingCancelled = true;
                log.info("Cancelled stale pending clarification {} (new non-selection turn)",
                        pending.contextItemId());
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
                CartGraphStateKeys.PENDING_CLARIFICATION_ID,
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
