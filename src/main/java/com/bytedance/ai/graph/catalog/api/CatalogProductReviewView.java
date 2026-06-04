package com.bytedance.ai.graph.catalog.api;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * User review view backed by catalog_product_review.
 */
public record CatalogProductReviewView(
        Long id,
        Long productId,
        Integer reviewIndex,
        String nickname,
        Integer rating,
        String content,
        String sentiment,
        Map<String, Object> metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public CatalogProductReviewView {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
