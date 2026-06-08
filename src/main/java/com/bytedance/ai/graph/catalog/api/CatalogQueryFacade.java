package com.bytedance.ai.graph.catalog.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Catalog 查询侧 Facade。
 *
 * <p>catalog 域统一以 {@code catalog_product} 为中心；历史的 SPU 兼容层
 * （{@code getSpu} / {@code findSpuByExternalRef} / {@code searchActiveSpus} 与
 * {@code CatalogSpuView}）已随新 DDL 移除。
 */
public interface CatalogQueryFacade {

    /**
     * 查询商品落地页详情（含全部 SKU）。
     *
     * @throws IllegalArgumentException 当 product 不存在
     */
    CatalogProductView getProduct(Long productId);

    /**
     * 批量查询商品摘要，供 product-query hydrate 使用；不包含 SKU。
     *
     * <p>默认实现保留兼容性，真实 catalog 实现应覆盖为批量 SQL。
     */
    default List<CatalogProductSummaryView> findProductSummaries(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        List<CatalogProductSummaryView> result = new ArrayList<>();
        for (Long productId : new LinkedHashSet<>(productIds)) {
            if (productId == null) {
                continue;
            }
            try {
                result.add(toSummaryView(getProduct(productId)));
            } catch (IllegalArgumentException ignored) {
                // Batch hydrate omits missing products.
            }
        }
        return result;
    }

    /**
     * 查询商品评论原文，供 review summary / 口碑类问答 hydrate 使用。
     */
    default List<CatalogProductReviewView> listReviews(Long productId, int limit) {
        return List.of();
    }

    /**
     * 批量查询商品评论原文，按 productId 分组。
     *
     * <p>默认实现保留兼容性，真实 catalog 实现应覆盖为批量 SQL。
     */
    default Map<Long, List<CatalogProductReviewView>> listReviewsByProductIds(
            List<Long> productIds,
            int limitPerProduct
    ) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<CatalogProductReviewView>> result = new LinkedHashMap<>();
        for (Long productId : new LinkedHashSet<>(productIds)) {
            if (productId != null) {
                result.put(productId, listReviews(productId, limitPerProduct));
            }
        }
        return result;
    }

    /**
     * PostgreSQL/catalog 关键词搜索，供购物车等写操作解析商品名时使用。
     */
    default List<CatalogProductView> searchActiveProducts(String keyword, int limit) {
        return List.of();
    }

    /**
     * 单独查询某商品的 SKU 列表。
     */
    List<CatalogSkuView> listSkus(Long productId);

    private static CatalogProductSummaryView toSummaryView(CatalogProductView view) {
        return new CatalogProductSummaryView(
                view.id(),
                view.title(),
                view.brand(),
                view.category(),
                view.subCategory(),
                view.priceMin(),
                view.priceMax(),
                view.totalStock(),
                view.attributes()
        );
    }
}
