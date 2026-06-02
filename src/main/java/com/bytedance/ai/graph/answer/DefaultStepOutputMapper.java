package com.bytedance.ai.graph.answer;

import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.cartmanage.CartManageAction;
import com.bytedance.ai.graph.cartmanage.CartManageWorkflowResult;
import com.bytedance.ai.graph.cartmanage.CartMutationResult;
import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import com.bytedance.ai.graph.conversation.context.ConversationRuntimeContext;
import com.bytedance.ai.graph.conversation.context.StepOutputMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认 StepOutputMapper：把 (intent, workflowResult, answerText) 投影成结构化 StepOutput。
 *
 * <p>已知 result 类型走专门提取；未知类型保留 {@code payload.raw} 兜底。
 * 各种 list 都封顶截断（候选 10 个、购物车条目 20 个），防止 prompt 爆量。
 */
@Component
public class DefaultStepOutputMapper implements StepOutputMapper {

    private static final int MAX_CANDIDATES = 10;
    private static final int MAX_CART_ITEMS = 20;

    @Override
    public ConversationRuntimeContext.StepOutput map(
            String intent, Object workflowResult, String answerText
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (StringUtils.hasText(answerText)) {
            payload.put("answer", answerText);
        }

        String kind;
        if (workflowResult instanceof CartManageWorkflowResult cart) {
            kind = mapCartResult(cart, payload);
        } else {
            kind = kindForIntent(intent);
            if (workflowResult != null) {
                // 未知 result 类型先原样保留；后续接入更多 workflow 时按需精细化
                payload.put("raw", workflowResult);
            }
        }
        return new ConversationRuntimeContext.StepOutput(kind, payload);
    }

    private String kindForIntent(String intent) {
        if (intent == null) {
            return KIND_UNKNOWN;
        }
        return switch (intent) {
            case "PRODUCT_SEARCH", "PRODUCT_RECOMMEND", "PRODUCT_COMPARE",
                 "PRODUCT_DETAIL_QUERY", "PRODUCT_QUERY" -> KIND_PRODUCT_CANDIDATES;
            case "PRICE_QUERY" -> KIND_PRICE_INFO;
            case "INVENTORY_QUERY" -> KIND_INVENTORY_INFO;
            case "ADD_TO_CART", "REMOVE_FROM_CART", "UPDATE_CART_ITEM", "CART_MANAGE" -> KIND_CART_MUTATION;
            case "CREATE_ORDER", "CONFIRM_ORDER", "CANCEL_ORDER" -> KIND_ORDER_MUTATION;
            case "ORDER_QUERY" -> KIND_ORDER_INFO;
            case "LOGISTICS_QUERY" -> KIND_LOGISTICS_INFO;
            case "POLICY_QA", "REVIEW_SUMMARY", "SMALL_TALK" -> KIND_TEXT_ANSWER;
            case "CLARIFY" -> KIND_CLARIFY_REQUEST;
            default -> KIND_UNKNOWN;
        };
    }

    /**
     * CartManageWorkflowResult 决定 kind：
     * <ul>
     *   <li>有 clarifyQuestion → CLARIFY_REQUEST</li>
     *   <li>action == VIEW_CART → CART_SNAPSHOT</li>
     *   <li>否则 → CART_MUTATION</li>
     * </ul>
     */
    private String mapCartResult(CartManageWorkflowResult cart, Map<String, Object> payload) {
        if (StringUtils.hasText(cart.clarifyQuestion())) {
            payload.put("question", cart.clarifyQuestion().strip());
            if (!cart.candidateItems().isEmpty()) {
                payload.put("candidateItems", projectList(cart.candidateItems(),
                        MAX_CANDIDATES, this::compactCartItem));
            }
            if (!cart.productCandidates().isEmpty()) {
                payload.put("productCandidates", projectList(cart.productCandidates(),
                        MAX_CANDIDATES, this::compactProductCandidate));
            }
            putIfPresent(payload, "pendingConfirmAction", cart.pendingConfirmAction());
            return KIND_CLARIFY_REQUEST;
        }

        if (cart.action() == CartManageAction.VIEW_CART && cart.cartBefore() != null) {
            compactCart(cart.cartBefore(), payload);
            return KIND_CART_SNAPSHOT;
        }

        // CART_MUTATION
        if (cart.action() != null) {
            payload.put("action", cart.action().name());
        }
        if (cart.targetItem() != null) {
            payload.put("targetItem", compactCartItem(cart.targetItem()));
        }
        if (!cart.productCandidates().isEmpty()) {
            payload.put("productCandidates", projectList(cart.productCandidates(),
                    MAX_CANDIDATES, this::compactProductCandidate));
        }
        if (cart.mutationResult() != null) {
            payload.put("mutationOutcome", compactMutationResult(cart.mutationResult()));
        }
        putIfPresent(payload, "pendingConfirmAction", cart.pendingConfirmAction());
        putIfPresent(payload, "errorCode", cart.errorCode());
        putIfPresent(payload, "errorMessage", cart.errorMessage());
        return KIND_CART_MUTATION;
    }

    private void compactCart(CartView cart, Map<String, Object> payload) {
        putIfPresent(payload, "itemCount", cart.itemCount());
        putIfPresent(payload, "subtotal", cart.subtotalAmount());
        putIfPresent(payload, "currency", cart.currency());
        if (cart.items() != null && !cart.items().isEmpty()) {
            payload.put("items", projectList(cart.items(), MAX_CART_ITEMS, this::compactCartItem));
        }
    }

    private Map<String, Object> compactCartItem(CartItemView item) {
        Map<String, Object> m = new LinkedHashMap<>();
        putIfPresent(m, "itemId", item.itemId());
        putIfPresent(m, "title", item.title());
        putIfPresent(m, "brand", item.brand());
        putIfPresent(m, "quantity", item.quantity());
        putIfPresent(m, "unitPrice", item.unitPrice());
        putIfPresent(m, "lineAmount", item.lineAmount());
        return m;
    }

    private Map<String, Object> compactProductCandidate(ProductCandidate p) {
        Map<String, Object> m = new LinkedHashMap<>();
        putIfPresent(m, "productId", p.productId());
        putIfPresent(m, "productName", p.productName());
        putIfPresent(m, "price", p.price());
        putIfPresent(m, "brief", p.brief());
        putIfPresent(m, "spec", p.spec());
        return m;
    }

    private Map<String, Object> compactMutationResult(CartMutationResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", r.success());
        if (r.updatedCart() != null) {
            putIfPresent(m, "cartItemCount", r.updatedCart().itemCount());
            putIfPresent(m, "cartSubtotal", r.updatedCart().subtotalAmount());
        }
        putIfPresent(m, "errorCode", r.errorCode());
        putIfPresent(m, "errorMessage", r.errorMessage());
        return m;
    }

    private <T> List<Map<String, Object>> projectList(
            List<T> source, int cap, java.util.function.Function<T, Map<String, Object>> mapper
    ) {
        return source.stream().limit(cap).map(mapper).toList();
    }

    private void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }
}
