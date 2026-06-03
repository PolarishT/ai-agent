package com.bytedance.ai.graph.answer;

import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.cartmanage.CartManageAction;
import com.bytedance.ai.graph.cartmanage.CartManageWorkflowResult;
import com.bytedance.ai.graph.cartmanage.CartMutationResult;
import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import com.bytedance.ai.graph.conversation.context.StepOutputMapper;
import com.bytedance.ai.graph.ordermanage.OrderManageWorkflowResult;
import com.bytedance.ai.graph.product.query.ProductComparisonResult;
import com.bytedance.ai.graph.product.query.ProductQueryWorkflowResult;
import com.bytedance.ai.graph.product.query.ProductSearchCandidate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认 StepOutputMapper：把 workflow 结果投影成 step.output map。
 *
 * <p>已知 result 类型走专门提取（PRODUCT_SEARCH → {@code {candidateCount, productInfo[]}}）；
 * 未知类型保留 {@code {raw: ...}} 兜底。各种 list 都封顶截断防止 prompt 爆量。
 */
@Component
public class DefaultStepOutputMapper implements StepOutputMapper {

    private static final int MAX_CANDIDATES = 10;
    private static final int MAX_CART_ITEMS = 20;

    @Override
    public Map<String, Object> toOutput(String taskType, Object workflowResult) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (workflowResult instanceof ProductQueryWorkflowResult pq) {
            fillProductResult(pq, output);
        } else if (workflowResult instanceof CartManageWorkflowResult cart) {
            fillCartResult(cart, output);
        } else if (workflowResult instanceof OrderManageWorkflowResult om) {
            fillOrderResult(om, output);
        } else if (workflowResult != null) {
            output.put("raw", workflowResult);
        }
        return output;
    }

    /** ProductQueryWorkflowResult → {candidateCount, productInfo[]}（+ comparison / clarify / degraded）。 */
    private void fillProductResult(ProductQueryWorkflowResult pq, Map<String, Object> output) {
        if ("CLARIFY".equals(pq.status())) {
            output.put("status", "CLARIFY");
            if (StringUtils.hasText(pq.nodeMessage())) {
                output.put("question", pq.nodeMessage());
            }
            return;
        }
        putIfPresent(output, "status", pq.status());
        output.put("candidateCount", pq.candidates().size());
        if (!pq.candidates().isEmpty()) {
            output.put("productInfo", projectList(pq.candidates(),
                    MAX_CANDIDATES, this::compactProductSearchCandidate));
        }
        if (pq.comparison() != null) {
            output.put("comparison", compactComparison(pq.comparison()));
        }
        if (!pq.degradedNotes().isEmpty()) {
            output.put("degradedNotes", pq.degradedNotes());
        }
    }

    /**
     * CartManageWorkflowResult：
     * 有 clarifyQuestion → {question, candidates}；VIEW_CART → cart 快照；否则 → mutation。
     */
    private void fillCartResult(CartManageWorkflowResult cart, Map<String, Object> output) {
        if (StringUtils.hasText(cart.clarifyQuestion())) {
            output.put("question", cart.clarifyQuestion().strip());
            if (!cart.candidateItems().isEmpty()) {
                output.put("candidateItems", projectList(cart.candidateItems(),
                        MAX_CANDIDATES, this::compactCartItem));
            }
            if (!cart.productCandidates().isEmpty()) {
                output.put("productCandidates", projectList(cart.productCandidates(),
                        MAX_CANDIDATES, this::compactProductCandidate));
            }
            putIfPresent(output, "pendingConfirmAction", cart.pendingConfirmAction());
            return;
        }

        if (cart.action() == CartManageAction.VIEW_CART && cart.cartBefore() != null) {
            compactCart(cart.cartBefore(), output);
            return;
        }

        if (cart.action() != null) {
            output.put("action", cart.action().name());
        }
        if (cart.targetItem() != null) {
            output.put("targetItem", compactCartItem(cart.targetItem()));
        }
        if (!cart.productCandidates().isEmpty()) {
            output.put("productCandidates", projectList(cart.productCandidates(),
                    MAX_CANDIDATES, this::compactProductCandidate));
        }
        if (cart.mutationResult() != null) {
            output.put("mutationOutcome", compactMutationResult(cart.mutationResult()));
        }
        putIfPresent(output, "pendingConfirmAction", cart.pendingConfirmAction());
        putIfPresent(output, "errorCode", cart.errorCode());
        putIfPresent(output, "errorMessage", cart.errorMessage());
    }

    /** OrderManageWorkflowResult → 扁平字段。 */
    private void fillOrderResult(OrderManageWorkflowResult om, Map<String, Object> output) {
        putIfPresent(output, "action", om.action());
        putIfPresent(output, "status", om.status());
        putIfPresent(output, "orderNo", om.orderNo());
        putIfPresent(output, "amount", om.amount());
        if (!om.addressSnapshot().isEmpty()) {
            output.put("addressSnapshot", om.addressSnapshot());
        }
        putIfPresent(output, "errorReason", om.errorReason());
        output.put("needUserInput", om.needUserInput());
    }

    /** 商品候选投影：对齐 productInfo 形态 {productId, title, brand, category, subCategory, price}。 */
    private Map<String, Object> compactProductSearchCandidate(ProductSearchCandidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        putIfPresent(m, "productId", c.productId());
        putIfPresent(m, "title", c.title());
        putIfPresent(m, "brand", c.brand());
        putIfPresent(m, "category", c.category());
        putIfPresent(m, "subCategory", c.subCategory());
        putIfPresent(m, "price", c.price());
        return m;
    }

    private Map<String, Object> compactComparison(ProductComparisonResult cmp) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!cmp.dimensions().isEmpty()) {
            m.put("dimensions", cmp.dimensions());
        }
        putIfPresent(m, "summary", cmp.summary());
        if (!cmp.rows().isEmpty()) {
            m.put("rows", projectList(cmp.rows(), MAX_CANDIDATES, this::compactComparisonRow));
        }
        return m;
    }

    private Map<String, Object> compactComparisonRow(ProductComparisonResult.Row row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", row.index());
        putIfPresent(m, "productId", row.productId());
        putIfPresent(m, "title", row.title());
        putIfPresent(m, "brand", row.brand());
        putIfPresent(m, "price", row.price());
        putIfPresent(m, "color", row.color());
        putIfPresent(m, "capacity", row.capacity());
        putIfPresent(m, "stock", row.stock());
        return m;
    }

    private void compactCart(CartView cart, Map<String, Object> output) {
        putIfPresent(output, "itemCount", cart.itemCount());
        putIfPresent(output, "subtotal", cart.subtotalAmount());
        putIfPresent(output, "currency", cart.currency());
        if (cart.items() != null && !cart.items().isEmpty()) {
            output.put("items", projectList(cart.items(), MAX_CART_ITEMS, this::compactCartItem));
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

    private void putIfPresent(Map<String, Object> output, String key, Object value) {
        if (value != null) {
            output.put(key, value);
        }
    }
}
