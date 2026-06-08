package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import com.bytedance.ai.graph.catalog.api.CatalogProductReviewView;
import com.bytedance.ai.graph.catalog.api.CatalogProductSummaryView;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductContentRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogSkuRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogSkuRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 商品目录查询服务，负责组装商品、SKU 和内容视图。
 */
@Service
class CatalogQueryService implements CatalogQueryFacade {

    private final CatalogProductRepository productRepository;
    private final CatalogSkuRepository skuRepository;
    private final CatalogProductContentRepository productContentRepository;

    CatalogQueryService(
            CatalogProductRepository productRepository,
            CatalogSkuRepository skuRepository,
            CatalogProductContentRepository productContentRepository
    ) {
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
        this.productContentRepository = productContentRepository;
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
    public List<CatalogProductSummaryView> findProductSummaries(List<Long> productIds) {
        List<Long> ids = normalizeProductIds(productIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, CatalogProductRecord> byId = new LinkedHashMap<>();
        for (CatalogProductRecord record : productRepository.findByIds(ids)) {
            byId.putIfAbsent(record.id(), record);
        }
        List<CatalogProductSummaryView> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            CatalogProductRecord record = byId.get(id);
            if (record != null) {
                result.add(toProductSummaryView(record));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public List<CatalogProductReviewView> listReviews(Long productId, int limit) {
        if (productId == null) {
            return List.of();
        }
        return productContentRepository.findReviewsByProductIds(List.of(productId), safeLimit(limit)).stream()
                .map(CatalogQueryService::toReviewView)
                .toList();
    }

    @Override
    public Map<Long, List<CatalogProductReviewView>> listReviewsByProductIds(
            List<Long> productIds,
            int limitPerProduct
    ) {
        List<Long> ids = normalizeProductIds(productIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<CatalogProductReviewView>> grouped = new LinkedHashMap<>();
        for (Long id : ids) {
            grouped.put(id, new ArrayList<>());
        }
        for (CatalogProductContentRepository.ReviewRecord record :
                productContentRepository.findReviewsByProductIds(ids, safeLimit(limitPerProduct))) {
            List<CatalogProductReviewView> reviews = grouped.get(record.productId());
            if (reviews != null) {
                reviews.add(toReviewView(record));
            }
        }
        Map<Long, List<CatalogProductReviewView>> result = new LinkedHashMap<>();
        grouped.forEach((productId, reviews) -> result.put(productId, List.copyOf(reviews)));
        return result;
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

    private static CatalogProductSummaryView toProductSummaryView(CatalogProductRecord record) {
        return new CatalogProductSummaryView(
                record.id(),
                record.title(),
                record.brand(),
                record.category(),
                record.subCategory(),
                record.priceMin(),
                record.priceMax(),
                record.totalStock(),
                record.attributesJson()
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

    private static CatalogProductReviewView toReviewView(CatalogProductContentRepository.ReviewRecord record) {
        return new CatalogProductReviewView(
                record.id(),
                record.productId(),
                record.reviewIndex(),
                record.nickname(),
                record.rating(),
                record.content(),
                record.sentiment(),
                record.metadata(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private static int safeLimit(int limit) {
        return limit <= 0 ? 5 : Math.min(limit, 20);
    }

    private static List<Long> normalizeProductIds(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        return productIds.stream()
                .filter(Objects::nonNull)
                .collect(LinkedHashSet<Long>::new, LinkedHashSet::add, LinkedHashSet::addAll)
                .stream()
                .toList();
    }
}
