package com.bytedance.ai.graph.catalog.persistence;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 商品目录仓储端口，隔离领域逻辑与底层持久化实现。
 */
public interface CatalogProductRepository {

    CatalogProductRecord save(
            String title,
            String brand,
            String category,
            String subCategory,
            BigDecimal basePrice,
            BigDecimal priceMin,
            BigDecimal priceMax,
            int totalStock,
            String imagePath,
            Map<String, Object> attributesJson,
            Map<String, Object> rawJson
    );

    Optional<CatalogProductRecord> findById(Long id);

    List<CatalogProductRecord> findByIds(Collection<Long> ids);

    List<CatalogProductRecord> searchActiveByKeyword(String keyword, int limit);

    boolean decreaseStock(Long productId, int quantity);

    boolean markAttributeExtractionRunning(Long productId);

    void markAttributeExtractionSucceeded(Long productId, Map<String, Object> attributesJson);

    void markAttributeExtractionFailed(Long productId, String errorMessage);
}
