package com.bytedance.ai.graph.catalog.api;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Lightweight product view for product-query hydration.
 *
 * <p>Unlike {@link CatalogProductView}, this does not carry SKU rows.
 */
public record CatalogProductSummaryView(
        Long id,
        String title,
        String brand,
        String category,
        String subCategory,
        BigDecimal priceMin,
        BigDecimal priceMax,
        Integer totalStock,
        Map<String, Object> attributes
) {
    public CatalogProductSummaryView {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
