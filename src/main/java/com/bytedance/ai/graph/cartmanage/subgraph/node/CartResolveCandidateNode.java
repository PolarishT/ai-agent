package com.bytedance.ai.graph.cartmanage.subgraph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import com.bytedance.ai.graph.cartmanage.persistence.PendingCartActionRecord;
import com.bytedance.ai.graph.cartmanage.persistence.PendingCartActionRepository;
import com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys;
import com.bytedance.ai.graph.cartmanage.subgraph.CartWorkflowStatus;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CandidateSelectionResolver;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CandidateSelectionResolver.CandidateSelectionResult;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CandidateSelectionResolver.CandidateSelectionStatus;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartGraphStateSupport;
import com.bytedance.ai.graph.orchestration.GuideGraphStateKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class CartResolveCandidateNode {

    private static final Logger log = LoggerFactory.getLogger(CartResolveCandidateNode.class);

    private final PendingCartActionRepository pendingCartActionRepository;
    private final CandidateSelectionResolver candidateSelectionResolver;

    public CartResolveCandidateNode(
            PendingCartActionRepository pendingCartActionRepository,
            CandidateSelectionResolver candidateSelectionResolver
    ) {
        this.pendingCartActionRepository = pendingCartActionRepository;
        this.candidateSelectionResolver = candidateSelectionResolver;
    }

    public Map<String, Object> apply(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        String userMessage = state.value(GuideGraphStateKeys.MESSAGE, "");
        String userId = CartGraphStateSupport.requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = CartGraphStateSupport.requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);

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
        CandidateSelectionResult selection = candidateSelectionResolver.resolve(userMessage, pending.candidates());
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

    public String routeAfter(OverAllState state) {
        String productId = state.value(CartGraphStateKeys.PRODUCT_ID, "");
        String skuId = state.value(CartGraphStateKeys.SKU_ID, "");
        if (StringUtils.hasText(productId) && StringUtils.hasText(skuId)) {
            log.info("Cart route after resolve candidate: productId={}, skuId={}, route=HAS_IDS", productId, skuId);
            return "HAS_IDS";
        }
        log.info("Cart route after resolve candidate: productId={}, skuId={}, route=FINAL", productId, skuId);
        return "FINAL";
    }
}
