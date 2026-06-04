package com.bytedance.ai.graph.product.query;

/**
 * Review text hydrated from catalog_product_review for answer generation.
 */
public record ProductReviewSnippet(
        Integer reviewIndex,
        String nickname,
        Integer rating,
        String sentiment,
        String content
) {
}
