package com.bytedance.ai.indexing.service;

import com.bytedance.ai.shared.metadata.RagChunkType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductChunkTypeNormalizerTests {

    @Test
    void productProfileSourceMapsToProductProfile() {
        assertThat(ProductChunkTypeNormalizer.normalize("PRODUCT_PROFILE", List.of(), null))
                .isEqualTo(RagChunkType.PRODUCT_PROFILE.name());
    }

    @Test
    void productKnowledgeSourceMapsToMarketing() {
        assertThat(ProductChunkTypeNormalizer.normalize("PRODUCT_KNOWLEDGE", List.of(), null))
                .isEqualTo(RagChunkType.MARKETING.name());
    }

    @Test
    void productReviewSummarySourceMapsToMarketing() {
        assertThat(ProductChunkTypeNormalizer.normalize("PRODUCT_REVIEW_SUMMARY", List.of(), null))
                .isEqualTo(RagChunkType.MARKETING.name());
    }

    @Test
    void productReviewSourceMapsToReview() {
        assertThat(ProductChunkTypeNormalizer.normalize("PRODUCT_REVIEW", List.of(), null))
                .isEqualTo(RagChunkType.REVIEW.name());
    }

    @Test
    void productFaqQuestionHeadingMapsToFaqQuery() {
        assertThat(ProductChunkTypeNormalizer.normalize("PRODUCT_FAQ", List.of("FAQ", "问题"), null))
                .isEqualTo(RagChunkType.FAQ_QUERY.name());
        assertThat(ProductChunkTypeNormalizer.normalize("PRODUCT_FAQ", List.of("FAQ", "Question"), null))
                .isEqualTo(RagChunkType.FAQ_QUERY.name());
    }

    @Test
    void productFaqAnswerHeadingMapsToFaqAnswer() {
        assertThat(ProductChunkTypeNormalizer.normalize("PRODUCT_FAQ", List.of("FAQ", "答案"), null))
                .isEqualTo(RagChunkType.FAQ_ANSWER.name());
        assertThat(ProductChunkTypeNormalizer.normalize("PRODUCT_FAQ", List.of("FAQ", "Answer"), null))
                .isEqualTo(RagChunkType.FAQ_ANSWER.name());
    }

    @Test
    void productFaqMissingHeadingDefaultsToFaqQuery() {
        assertThat(ProductChunkTypeNormalizer.normalize("PRODUCT_FAQ", List.of("FAQ"), null))
                .isEqualTo(RagChunkType.FAQ_QUERY.name());
        assertThat(ProductChunkTypeNormalizer.normalize("PRODUCT_FAQ", null, null))
                .isEqualTo(RagChunkType.FAQ_QUERY.name());
    }

    @Test
    void explicitChunkTypeOverridesSourceTypeRule() {
        assertThat(ProductChunkTypeNormalizer.normalize("PRODUCT_PROFILE", List.of(), RagChunkType.MARKETING))
                .as("显式标注的 MARKETING 优先于 sourceType-based 归一")
                .isEqualTo(RagChunkType.MARKETING.name());
    }

    @Test
    void unknownSourceFallsBackToProductProfile() {
        assertThat(ProductChunkTypeNormalizer.normalize("UNKNOWN_SOURCE", List.of(), null))
                .isEqualTo(RagChunkType.PRODUCT_PROFILE.name());
        assertThat(ProductChunkTypeNormalizer.normalize(null, List.of(), null))
                .isEqualTo(RagChunkType.PRODUCT_PROFILE.name());
    }
}
