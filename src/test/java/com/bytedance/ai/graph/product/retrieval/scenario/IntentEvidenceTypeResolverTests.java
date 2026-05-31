package com.bytedance.ai.graph.product.retrieval.scenario;

import com.bytedance.ai.graph.product.query.ProductQueryIntent;
import com.bytedance.ai.shared.metadata.RagChunkType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentEvidenceTypeResolverTests {

    private final IntentEvidenceTypeResolver resolver = new IntentEvidenceTypeResolver();

    @Test
    void productSearchAndCompareGoThroughProductProfileOnly() {
        assertThat(resolver.resolve(ProductQueryIntent.PRODUCT_SEARCH))
                .containsExactly(RagChunkType.PRODUCT_PROFILE);
        assertThat(resolver.resolve(ProductQueryIntent.PRODUCT_COMPARE))
                .containsExactly(RagChunkType.PRODUCT_PROFILE);
    }

    @Test
    void productRecommendCombinesProfileAndMarketing() {
        // 推荐场景：PROFILE 决定可售商品，MARKETING 补充推荐理由 / 卖点
        assertThat(resolver.resolve(ProductQueryIntent.PRODUCT_RECOMMEND))
                .containsExactly(RagChunkType.PRODUCT_PROFILE, RagChunkType.MARKETING);
    }

    @Test
    void inventoryAndPriceGoThroughProductProfileOnly() {
        assertThat(resolver.resolve(ProductQueryIntent.INVENTORY_CHECK))
                .containsExactly(RagChunkType.PRODUCT_PROFILE);
        assertThat(resolver.resolve(ProductQueryIntent.PRICE_QA))
                .containsExactly(RagChunkType.PRODUCT_PROFILE);
    }

    @Test
    void productQaCombinesProfileAndMarketing() {
        assertThat(resolver.resolve(ProductQueryIntent.PRODUCT_QA))
                .containsExactly(RagChunkType.PRODUCT_PROFILE, RagChunkType.MARKETING);
    }

    @Test
    void reviewQaUsesReviewEvidenceOnly() {
        assertThat(resolver.resolve(ProductQueryIntent.REVIEW_QA))
                .containsExactly(RagChunkType.REVIEW);
    }

    @Test
    void faqQaCombinesQueryAndAnswerEvidence() {
        assertThat(resolver.resolve(ProductQueryIntent.FAQ_QA))
                .containsExactly(RagChunkType.FAQ_QUERY, RagChunkType.FAQ_ANSWER);
    }

    @Test
    void nullIntentFallsBackToProductProfile() {
        assertThat(resolver.resolve(null)).containsExactly(RagChunkType.PRODUCT_PROFILE);
    }
}
