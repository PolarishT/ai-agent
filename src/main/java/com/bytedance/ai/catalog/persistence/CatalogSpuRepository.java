package com.bytedance.ai.catalog.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * catalog_spu 仓储。
 *
 * <p>状态机：attributes_status 由 catalog 内部 worker 驱动；status 表征商品上下架业务态。
 * 不依赖数据库外键，与 catalog_sku、rag_documents 的关联由应用层维护。
 */
public interface CatalogSpuRepository {

    /**
     * 新建 SPU 主记录。document_id 在双写完成后通过 {@link #attachDocument} 回填。
     */
    CatalogSpuRecord save(
            String externalRef,
            String title,
            String brand,
            String categoryPath,
            BigDecimal priceMin,
            BigDecimal priceMax,
            int stock,
            String descriptionMd,
            List<String> images,
            String videoUrl
    );

    /**
     * 双写 rag_documents 成功后，把 documentId 回填到 SPU 主记录上。
     */
    void attachDocument(Long spuId, Long documentId);

    Optional<CatalogSpuRecord> findById(Long id);

    Optional<CatalogSpuRecord> findByExternalRef(String externalRef);

    boolean decreaseStock(Long spuId, int quantity);

    /**
     * 标记一次属性抽取尝试开始：PENDING/FAILED -> RUNNING，并累加 attempt_count。
     * 若当前状态非 PENDING/FAILED，则返回 false（避免并发覆盖）。
     */
    boolean markAttributeExtractionRunning(Long spuId);

    /**
     * 标记属性抽取成功并回填 attributes_json。
     */
    void markAttributeExtractionSucceeded(Long spuId, Map<String, Object> attributesJson);

    /**
     * 标记属性抽取失败，附带错误信息。
     */
    void markAttributeExtractionFailed(Long spuId, String errorMessage);

    /**
     * 列出所有处于 PENDING 状态的 SPU，供手动重试或补偿任务使用。
     */
    List<CatalogSpuRecord> findByAttributesStatus(String status, int limit);
}
