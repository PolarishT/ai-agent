package com.bytedance.ai.graph.catalog.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * catalog_product row.
 */
public record CatalogProductRecord(
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
        Map<String, Object> attributesJson,
        Map<String, Object> rawJson,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
