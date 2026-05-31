package com.bytedance.ai.graph.catalog.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * catalog_sku 表的记录模型。
 */
public record CatalogSkuRecord(
        Long id,
        Long productId,
        Integer skuIndex,
        Map<String, Object> propertiesJson,
        BigDecimal price,
        Integer stock,
        String status,
        Map<String, Object> rawJson,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    @Deprecated
    public Long spuId() {
        return productId;
    }

    @Deprecated
    public String skuCode() {
        Object rawSkuId = rawJson == null ? null : rawJson.get("sku_id");
        return rawSkuId == null ? String.valueOf(skuIndex) : String.valueOf(rawSkuId);
    }

    @Deprecated
    public Map<String, Object> specJson() {
        return propertiesJson;
    }
}
