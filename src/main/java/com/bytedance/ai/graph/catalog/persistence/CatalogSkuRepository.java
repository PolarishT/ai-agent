package com.bytedance.ai.graph.catalog.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * catalog_sku 仓储。
 */
public interface CatalogSkuRepository {

    List<CatalogSkuRecord> saveAll(Long productId, List<SkuDraft> drafts);

    List<CatalogSkuRecord> findByProductId(Long productId);

    @Deprecated
    default List<CatalogSkuRecord> findBySpuId(Long productId) {
        return findByProductId(productId);
    }

    /**
     * SKU 插入草稿——避免接口里堆参数。
     *
     * @param skuIndex       SKU 数组下标
     * @param propertiesJson 规格 KV
     * @param price    价格
     * @param stock    库存
     * @param rawJson  原始评委 SKU payload
     */
    record SkuDraft(
            int skuIndex,
            Map<String, Object> propertiesJson,
            BigDecimal price,
            int stock,
            Map<String, Object> rawJson
    ) {
    }
}
