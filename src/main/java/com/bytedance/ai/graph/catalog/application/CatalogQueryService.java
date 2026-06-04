package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import com.bytedance.ai.graph.catalog.api.CatalogProductReviewView;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.catalog.persistence.CatalogSkuRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogSkuRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductRepository;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 商品目录查询服务，负责组装商品、SKU 和内容视图。
 */
@Service
class CatalogQueryService implements CatalogQueryFacade {

    private final CatalogProductRepository productRepository;
    private final CatalogSkuRepository skuRepository;
    private final JdbcTemplate jdbc;
    private final RagJsonCodec jsonCodec;

    CatalogQueryService(
            CatalogProductRepository productRepository,
            CatalogSkuRepository skuRepository,
            JdbcTemplate jdbc,
            RagJsonCodec jsonCodec
    ) {
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public CatalogProductView getProduct(Long productId) {
        CatalogProductRecord record = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("catalog_product 不存在: " + productId));
        List<CatalogSkuView> skuViews = skuRepository.findByProductId(productId).stream()
                .map(CatalogQueryService::toSkuView)
                .toList();
        return toProductView(record, skuViews);
    }

    @Override
    public List<CatalogProductReviewView> listReviews(Long productId, int limit) {
        if (productId == null) {
            return List.of();
        }
        int safeLimit = limit <= 0 ? 5 : Math.min(limit, 20);
        return jdbc.query(
                """
                SELECT id, product_id, review_index, nickname, rating, content, sentiment,
                       metadata, created_at, updated_at
                  FROM catalog_product_review
                 WHERE product_id = ?
                 ORDER BY review_index ASC, id ASC
                 LIMIT ?
                """,
                (rs, rowNum) -> new CatalogProductReviewView(
                        rs.getLong("id"),
                        rs.getLong("product_id"),
                        rs.getInt("review_index"),
                        rs.getString("nickname"),
                        rs.getObject("rating", Integer.class),
                        rs.getString("content"),
                        rs.getString("sentiment"),
                        readMetadata(rs.getString("metadata")),
                        toOffsetDateTime(rs.getTimestamp("created_at")),
                        toOffsetDateTime(rs.getTimestamp("updated_at"))
                ),
                productId,
                safeLimit
        );
    }

    @Override
    public List<CatalogProductView> searchActiveProducts(String keyword, int limit) {
        int safeLimit = limit <= 0 ? 5 : Math.min(limit, 20);
        return productRepository.searchActiveByKeyword(keyword, safeLimit).stream()
                .map(record -> {
                    List<CatalogSkuView> skuViews = skuRepository.findByProductId(record.id()).stream()
                            .map(CatalogQueryService::toSkuView)
                            .toList();
                    return toProductView(record, skuViews);
                })
                .toList();
    }

    @Override
    public List<CatalogSkuView> listSkus(Long productId) {
        return skuRepository.findByProductId(productId).stream()
                .map(CatalogQueryService::toSkuView)
                .toList();
    }

    private static CatalogProductView toProductView(CatalogProductRecord record, List<CatalogSkuView> skus) {
        return new CatalogProductView(
                record.id(),
                record.title(),
                record.brand(),
                record.category(),
                record.subCategory(),
                record.basePrice(),
                record.priceMin(),
                record.priceMax(),
                record.totalStock(),
                record.imagePath(),
                record.status(),
                record.attributesJson(),
                record.rawJson(),
                skus,
                record.createdAt(),
                record.updatedAt()
        );
    }

    private static CatalogSkuView toSkuView(CatalogSkuRecord record) {
        return new CatalogSkuView(
                record.id(),
                String.valueOf(record.skuIndex()),
                record.propertiesJson(),
                record.price(),
                record.stock(),
                record.status()
        );
    }

    private Map<String, Object> readMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return Map.of();
        }
        return jsonCodec.readMap(metadata);
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
