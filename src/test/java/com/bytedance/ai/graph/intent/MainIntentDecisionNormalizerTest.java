package com.bytedance.ai.graph.intent;

import com.bytedance.ai.graph.intent.service.MainIntentDecisionNormalizer;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class MainIntentDecisionNormalizerTest {

    private final MainIntentDecisionNormalizer normalizer = new MainIntentDecisionNormalizer();

    @Test
    void nullIntentWithLowDefaultConfidenceFallsBackToClarify() {
        MainIntentDecision normalized = normalizer.normalize(decision(null, 0.0d, false, true, "evil"));

        assertThat(normalized.intent()).isEqualTo(MainIntent.CLARIFY);
        assertThat(normalized.needClarify()).isTrue();
        assertThat(normalized.targetWorkflow()).isEqualTo("clarify_workflow");
    }

    @Test
    void clampsConfidenceAboveOne() {
        MainIntentDecision normalized = normalizer.normalize(decision(MainIntent.PRICE_QUERY, 1.4d, false, true, "evil"));

        assertThat(normalized.confidence()).isEqualTo(1.0d);
    }

    @Test
    void clampsConfidenceBelowZeroAndForcesClarify() {
        MainIntentDecision normalized = normalizer.normalize(decision(MainIntent.PRICE_QUERY, -0.2d, false, false, "evil"));

        assertThat(normalized.confidence()).isEqualTo(0.0d);
        assertThat(normalized.intent()).isEqualTo(MainIntent.CLARIFY);
    }

    @Test
    void lowConfidenceForcesClarify() {
        MainIntentDecision normalized = normalizer.normalize(decision(MainIntent.PRODUCT_SEARCH, 0.54d, false, false, "evil"));

        assertThat(normalized.intent()).isEqualTo(MainIntent.CLARIFY);
        assertThat(normalized.needClarify()).isTrue();
    }

    @Test
    void createOrderForcesWriteActionTrue() {
        MainIntentDecision normalized = normalizer.normalize(new MainIntentDecision(
                MainIntent.CREATE_ORDER,
                0.9d,
                false,
                false,
                "evil",
                null,
                Map.of("cartId", "cart-1"),
                List.of()
        ));

        assertThat(normalized.writeAction()).isTrue();
    }

    @Test
    void priceQueryForcesWriteActionFalse() {
        MainIntentDecision normalized = normalizer.normalize(decision(MainIntent.PRICE_QUERY, 0.9d, false, true, "evil"));

        assertThat(normalized.writeAction()).isFalse();
    }

    @Test
    void targetWorkflowIsRegeneratedByBackend() {
        MainIntentDecision normalized = normalizer.normalize(decision(MainIntent.PRICE_QUERY, 0.9d, false, false, "add_to_cart_workflow"));

        assertThat(normalized.targetWorkflow()).isEqualTo("price_query_workflow");
    }

    @Test
    void addToCartIsRewrittenToCartManageWithCartActionAdd() {
        MainIntentDecision normalized = normalizer.normalize(new MainIntentDecision(
                MainIntent.ADD_TO_CART,
                0.92d,
                false,
                true,
                "add_to_cart_workflow",
                "llm reason",
                Map.of("productName", "轻量通勤双肩包 14 寸防水"),
                List.of()
        ));

        assertThat(normalized.intent()).isEqualTo(MainIntent.CART_MANAGE);
        assertThat(normalized.targetWorkflow()).isEqualTo("cart_manage_workflow");
        assertThat(normalized.slots()).containsEntry("cart_action", "ADD");
        assertThat(normalized.slots()).containsEntry("product_name", "轻量通勤双肩包 14 寸防水");
        assertThat(normalized.slots()).doesNotContainKey("productName");
        assertThat(normalized.missingSlots()).isEmpty();
        assertThat(normalized.needClarify()).isFalse();
        assertThat(normalized.writeAction()).isTrue();
    }

    @Test
    void removeFromCartIsRewrittenToCartManageWithCartActionRemove() {
        MainIntentDecision normalized = normalizer.normalize(new MainIntentDecision(
                MainIntent.REMOVE_FROM_CART,
                0.88d,
                false,
                true,
                "remove_from_cart_workflow",
                "llm reason",
                Map.of("productRef", "洗面奶"),
                List.of()
        ));

        assertThat(normalized.intent()).isEqualTo(MainIntent.CART_MANAGE);
        assertThat(normalized.targetWorkflow()).isEqualTo("cart_manage_workflow");
        assertThat(normalized.slots()).containsEntry("cart_action", "REMOVE");
        assertThat(normalized.slots()).containsEntry("product_ref", "洗面奶");
        assertThat(normalized.missingSlots()).isEmpty();
    }

    @Test
    void updateCartItemIsRewrittenToCartManageWithCartActionUpdateQuantity() {
        MainIntentDecision normalized = normalizer.normalize(new MainIntentDecision(
                MainIntent.UPDATE_CART_ITEM,
                0.81d,
                false,
                true,
                "update_cart_item_workflow",
                "llm reason",
                Map.of("cartItemId", "42", "quantity", 3),
                List.of()
        ));

        assertThat(normalized.intent()).isEqualTo(MainIntent.CART_MANAGE);
        assertThat(normalized.targetWorkflow()).isEqualTo("cart_manage_workflow");
        assertThat(normalized.slots()).containsEntry("cart_action", "UPDATE_QUANTITY");
        assertThat(normalized.slots()).containsEntry("cart_item_id", "42");
        assertThat(normalized.slots()).containsEntry("quantity", 3);
    }

    @Test
    void slotKeyNormalizationPrefersSnakeCaseWhenBothFormsPresent() {
        MainIntentDecision normalized = normalizer.normalize(new MainIntentDecision(
                MainIntent.ADD_TO_CART,
                0.9d,
                false,
                true,
                "evil",
                "llm reason",
                Map.of("productName", "from_camel", "product_name", "from_snake"),
                List.of()
        ));

        assertThat(normalized.slots()).containsEntry("product_name", "from_snake");
        assertThat(normalized.slots()).doesNotContainKey("productName");
    }

    @Test
    void nullCollectionsAreNormalized() {
        MainIntentDecision normalized = normalizer.normalize(new MainIntentDecision(
                MainIntent.PRICE_QUERY,
                0.9d,
                false,
                false,
                "evil",
                null,
                null,
                null
        ));

        assertThat(normalized.slots()).isEmpty();
        assertThat(normalized.missingSlots()).isEmpty();
        assertThat(normalized.reason()).isEmpty();
    }

    private MainIntentDecision decision(
            MainIntent intent,
            double confidence,
            boolean needClarify,
            boolean writeAction,
            String targetWorkflow
    ) {
        return new MainIntentDecision(
                intent,
                confidence,
                needClarify,
                writeAction,
                targetWorkflow,
                "llm reason",
                Map.of(),
                List.of()
        );
    }
}
