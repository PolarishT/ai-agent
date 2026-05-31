package com.bytedance.ai.graph.catalog.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Product landing-page view backed by catalog_product.
 */
public record CatalogProductView(
        Long id,
        String title,
        String brand,
        String category,
        String subCategory,
        BigDecimal basePrice,
        BigDecimal priceMin,
        BigDecimal priceMax,
        Integer totalStock,
        String imagePath,
        String status,
        Map<String, Object> attributes,
        Map<String, Object> rawJson,
        List<CatalogSkuView> skus,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
