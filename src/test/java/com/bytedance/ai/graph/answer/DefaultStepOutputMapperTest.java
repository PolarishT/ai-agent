package com.bytedance.ai.graph.answer;

import com.bytedance.ai.graph.cart.api.CartState;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.cartmanage.CartManageAction;
import com.bytedance.ai.graph.cartmanage.CartManageWorkflowResult;
import com.bytedance.ai.graph.ordermanage.OrderManageWorkflowResult;
import com.bytedance.ai.graph.product.query.ProductQueryWorkflowResult;
import com.bytedance.ai.graph.product.query.ProductReviewSnippet;
import com.bytedance.ai.graph.product.query.ProductSearchCandidate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultStepOutputMapperTest {

    private final DefaultStepOutputMapper mapper = new DefaultStepOutputMapper();

    @Test
    void productResultProjectsCandidateCountAndProductInfo() {
        ProductSearchCandidate c = new ProductSearchCandidate(
                201L, "EXT-201", "维他柠檬茶 250ml×6", "维他", "食品饮料", "茶饮料",
                new BigDecimal("13.9"), 50, Map.of(), 0.8, 0.7, 0.9, List.of("品类匹配"),
                List.of(new ProductReviewSnippet(0, "阿明", 5, "POSITIVE", "味道清爽")));
        ProductQueryWorkflowResult pq = new ProductQueryWorkflowResult(
                "OK", List.of(c), null, List.of(), "找到 1 款");

        Map<String, Object> out = mapper.toOutput("PRODUCT_SEARCH", pq);

        assertThat(out).containsEntry("candidateCount", 1).containsEntry("status", "OK");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> info = (List<Map<String, Object>>) out.get("productInfo");
        assertThat(info).hasSize(1);
        assertThat(info.get(0))
                .containsEntry("productId", 201L)
                .containsEntry("title", "维他柠檬茶 250ml×6")
                .containsEntry("brand", "维他")
                .containsEntry("category", "食品饮料")
                .containsEntry("subCategory", "茶饮料")
                .containsEntry("price", new BigDecimal("13.9"))
                .doesNotContainKey("scoreFinal");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviews = (List<Map<String, Object>>) info.get(0).get("reviews");
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0))
                .containsEntry("rating", 5)
                .containsEntry("content", "味道清爽");
    }

    @Test
    void productClarifyExposesQuestion() {
        ProductQueryWorkflowResult pq = new ProductQueryWorkflowResult(
                "CLARIFY", List.of(), null, List.of(), "想要哪个容量？");

        Map<String, Object> out = mapper.toOutput("PRODUCT_SEARCH", pq);

        assertThat(out).containsEntry("status", "CLARIFY").containsEntry("question", "想要哪个容量？");
        assertThat(out).doesNotContainKey("productInfo");
    }

    @Test
    void cartViewProducesSnapshot() {
        CartView view = new CartView("cart-1", "u1", "c1", CartState.IDLE, "CNY",
                new BigDecimal("198.00"), 2, Map.of(), List.of());
        CartManageWorkflowResult res = new CartManageWorkflowResult(
                CartManageAction.VIEW_CART, null, view, null, List.of(), List.of(),
                null, null, null, null, null, null, null);

        Map<String, Object> out = mapper.toOutput("CART_MANAGE", res);

        assertThat(out).containsEntry("itemCount", 2)
                .containsEntry("subtotal", new BigDecimal("198.00"))
                .containsEntry("currency", "CNY");
    }

    @Test
    void orderCreatedExposesOrderNoAndAmount() {
        OrderManageWorkflowResult om = new OrderManageWorkflowResult(
                "CONFIRM_ORDER", "ORDER_CREATED", "ORD-2026-001",
                new BigDecimal("287.00"), Map.of("name", "Zhang"), null, false, "下单成功");

        Map<String, Object> out = mapper.toOutput("CONFIRM_ORDER", om);

        assertThat(out)
                .containsEntry("action", "CONFIRM_ORDER")
                .containsEntry("status", "ORDER_CREATED")
                .containsEntry("orderNo", "ORD-2026-001")
                .containsEntry("amount", new BigDecimal("287.00"))
                .containsEntry("needUserInput", false);
    }

    @Test
    void unknownResultKeptAsRaw() {
        Map<String, Object> result = Map.of("foo", "bar");
        Map<String, Object> out = mapper.toOutput("PRODUCT_SEARCH", result);
        assertThat(out).containsEntry("raw", result);
    }

    @Test
    void nullResultYieldsEmptyOutput() {
        assertThat(mapper.toOutput("PRICE_QUERY", null)).isEmpty();
    }
}
