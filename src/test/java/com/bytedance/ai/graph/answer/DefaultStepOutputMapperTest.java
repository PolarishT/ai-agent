package com.bytedance.ai.graph.answer;

import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartState;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.cartmanage.CartManageAction;
import com.bytedance.ai.graph.cartmanage.CartManageWorkflowResult;
import com.bytedance.ai.graph.cartmanage.CartMutationResult;
import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import com.bytedance.ai.graph.conversation.context.ConversationRuntimeContext;
import com.bytedance.ai.graph.conversation.context.StepOutputMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultStepOutputMapperTest {

    private final DefaultStepOutputMapper mapper = new DefaultStepOutputMapper();

    @Test
    void productGroupIntentsAllMapToProductCandidatesKind() {
        for (String intent : List.of("PRODUCT_SEARCH", "PRODUCT_RECOMMEND",
                "PRODUCT_COMPARE", "PRODUCT_DETAIL_QUERY", "PRODUCT_QUERY")) {
            ConversationRuntimeContext.StepOutput out = mapper.map(intent, null, "ok");
            assertThat(out.kind())
                    .as("intent %s -> kind", intent)
                    .isEqualTo(StepOutputMapper.KIND_PRODUCT_CANDIDATES);
        }
    }

    @Test
    void miscIntentsMapToTheirSpecificKinds() {
        assertThat(mapper.map("PRICE_QUERY", null, "p").kind()).isEqualTo(StepOutputMapper.KIND_PRICE_INFO);
        assertThat(mapper.map("INVENTORY_QUERY", null, "i").kind()).isEqualTo(StepOutputMapper.KIND_INVENTORY_INFO);
        assertThat(mapper.map("CREATE_ORDER", null, "o").kind()).isEqualTo(StepOutputMapper.KIND_ORDER_MUTATION);
        assertThat(mapper.map("ORDER_QUERY", null, "q").kind()).isEqualTo(StepOutputMapper.KIND_ORDER_INFO);
        assertThat(mapper.map("LOGISTICS_QUERY", null, "l").kind()).isEqualTo(StepOutputMapper.KIND_LOGISTICS_INFO);
        assertThat(mapper.map("POLICY_QA", null, "x").kind()).isEqualTo(StepOutputMapper.KIND_TEXT_ANSWER);
        assertThat(mapper.map("CLARIFY", null, "?").kind()).isEqualTo(StepOutputMapper.KIND_CLARIFY_REQUEST);
        assertThat(mapper.map("RANDOM_UNKNOWN", null, "u").kind()).isEqualTo(StepOutputMapper.KIND_UNKNOWN);
        assertThat(mapper.map(null, null, null).kind()).isEqualTo(StepOutputMapper.KIND_UNKNOWN);
    }

    @Test
    void cartViewResultProducesCartSnapshotWithCompactItems() {
        CartItemView item1 = new CartItemView(
                11L, 101L, "EXT-101", "防晒霜 SPF50", "理肤泉",
                "img://1", 2, new BigDecimal("99.00"), new BigDecimal("198.00"), 50);
        CartView view = new CartView(
                "cart-1", "u1", "c1", CartState.IDLE, "CNY",
                new BigDecimal("198.00"), 2, Map.of(),
                List.of(item1));
        CartManageWorkflowResult res = cartResult(CartManageAction.VIEW_CART, view, null, null, null, null, null);

        ConversationRuntimeContext.StepOutput out = mapper.map("CART_MANAGE", res, "查看购物车");

        assertThat(out.kind()).isEqualTo(StepOutputMapper.KIND_CART_SNAPSHOT);
        assertThat(out.payload())
                .containsEntry("answer", "查看购物车")
                .containsEntry("itemCount", 2)
                .containsEntry("subtotal", new BigDecimal("198.00"))
                .containsEntry("currency", "CNY");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.payload().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0))
                .containsEntry("title", "防晒霜 SPF50")
                .containsEntry("quantity", 2)
                .containsEntry("unitPrice", new BigDecimal("99.00"));
    }

    @Test
    void cartAddWithCandidatesProducesCartMutationWithProductCandidates() {
        ProductCandidate c1 = new ProductCandidate("p1", null,
                "防晒霜 A", new BigDecimal("99.00"), "理肤泉", "SPF50", "p1");
        ProductCandidate c2 = new ProductCandidate("p2", null,
                "防晒霜 B", new BigDecimal("129.00"), "薇姿", "SPF30", "p2");
        CartManageWorkflowResult res = cartResult(CartManageAction.ADD,
                null, null, null, List.of(c1, c2), null, null);

        ConversationRuntimeContext.StepOutput out = mapper.map("ADD_TO_CART", res, "请选择");

        assertThat(out.kind()).isEqualTo(StepOutputMapper.KIND_CART_MUTATION);
        assertThat(out.payload()).containsEntry("action", "ADD");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates =
                (List<Map<String, Object>>) out.payload().get("productCandidates");
        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0))
                .containsEntry("productId", "p1")
                .containsEntry("productName", "防晒霜 A")
                .containsEntry("price", new BigDecimal("99.00"));
    }

    @Test
    void cartRemoveWithTargetItemAndMutationOutcome() {
        CartItemView target = new CartItemView(
                22L, 202L, "EXT-202", "面膜", "WIS",
                null, 3, new BigDecimal("39.00"), new BigDecimal("117.00"), 20);
        CartView updated = new CartView(
                "cart-1", "u1", "c1", CartState.IDLE, "CNY",
                new BigDecimal("0.00"), 0, Map.of(), List.of());
        CartMutationResult mutation = CartMutationResult.ok(updated);
        CartManageWorkflowResult res = cartResult(CartManageAction.REMOVE_ITEM,
                null, target, null, null, mutation, null);

        ConversationRuntimeContext.StepOutput out = mapper.map("REMOVE_FROM_CART", res, "已删除");

        assertThat(out.kind()).isEqualTo(StepOutputMapper.KIND_CART_MUTATION);
        assertThat(out.payload()).containsEntry("action", "REMOVE_ITEM");
        @SuppressWarnings("unchecked")
        Map<String, Object> targetMap = (Map<String, Object>) out.payload().get("targetItem");
        assertThat(targetMap)
                .containsEntry("itemId", 22L)
                .containsEntry("title", "面膜")
                .containsEntry("quantity", 3);
        @SuppressWarnings("unchecked")
        Map<String, Object> outcome = (Map<String, Object>) out.payload().get("mutationOutcome");
        assertThat(outcome)
                .containsEntry("success", true)
                .containsEntry("cartItemCount", 0)
                .containsEntry("cartSubtotal", new BigDecimal("0.00"));
    }

    @Test
    void cartResultWithClarifyQuestionProducesClarifyRequest() {
        CartItemView item1 = new CartItemView(
                11L, 101L, "X", "防晒霜 A", "B1", null, 1,
                new BigDecimal("99.00"), new BigDecimal("99.00"), 10);
        CartItemView item2 = new CartItemView(
                12L, 102L, "Y", "防晒霜 B", "B2", null, 1,
                new BigDecimal("129.00"), new BigDecimal("129.00"), 10);
        CartManageWorkflowResult res = new CartManageWorkflowResult(
                CartManageAction.REMOVE_ITEM, null, null, null,
                List.of(item1, item2), List.of(), null, null,
                "你想删除哪一个？", null, null, null, null
        );

        ConversationRuntimeContext.StepOutput out = mapper.map("CART_MANAGE", res, null);

        assertThat(out.kind()).isEqualTo(StepOutputMapper.KIND_CLARIFY_REQUEST);
        assertThat(out.payload())
                .containsEntry("question", "你想删除哪一个？")
                .doesNotContainKey("answer"); // null answer 不写
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidateItems =
                (List<Map<String, Object>>) out.payload().get("candidateItems");
        assertThat(candidateItems).hasSize(2);
    }

    @Test
    void unknownWorkflowResultPreservedAsRaw() {
        Map<String, Object> result = Map.of("foo", "bar", "n", 7);

        ConversationRuntimeContext.StepOutput out = mapper.map("PRODUCT_SEARCH", result, "ok");

        assertThat(out.kind()).isEqualTo(StepOutputMapper.KIND_PRODUCT_CANDIDATES);
        assertThat(out.payload())
                .containsEntry("answer", "ok")
                .containsEntry("raw", result);
    }

    @Test
    void nullWorkflowResultProducesOnlyAnswer() {
        ConversationRuntimeContext.StepOutput out = mapper.map("PRICE_QUERY", null, "9.9 元");

        assertThat(out.kind()).isEqualTo(StepOutputMapper.KIND_PRICE_INFO);
        assertThat(out.payload())
                .containsEntry("answer", "9.9 元")
                .doesNotContainKey("raw");
    }

    @Test
    void candidatesAreCappedAtTenItems() {
        List<ProductCandidate> manyCandidates = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            manyCandidates.add(new ProductCandidate("p" + i, null, "name " + i,
                    new BigDecimal(i), "b", "s", "e"));
        }
        CartManageWorkflowResult res = cartResult(CartManageAction.ADD,
                null, null, null, manyCandidates, null, null);

        ConversationRuntimeContext.StepOutput out = mapper.map("ADD_TO_CART", res, "请选");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates =
                (List<Map<String, Object>>) out.payload().get("productCandidates");
        assertThat(candidates).hasSize(10);
    }

    private static CartManageWorkflowResult cartResult(
            CartManageAction action,
            CartView cartBefore,
            CartItemView targetItem,
            List<CartItemView> candidateItems,
            List<ProductCandidate> productCandidates,
            CartMutationResult mutationResult,
            String clarifyQuestion
    ) {
        return new CartManageWorkflowResult(
                action,
                null,
                cartBefore,
                targetItem,
                candidateItems == null ? List.of() : candidateItems,
                productCandidates == null ? List.of() : productCandidates,
                mutationResult,
                null,
                clarifyQuestion,
                null,
                null,
                null,
                null
        );
    }
}
