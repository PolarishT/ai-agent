package com.bytedance.ai.graph.catalog.api;

import java.util.List;

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
     * PostgreSQL/catalog 关键词搜索，供购物车等写操作解析商品名时使用。
     */
    default List<CatalogProductView> searchActiveProducts(String keyword, int limit) {
        return List.of();
    }

    /**
     * 单独查询某商品的 SKU 列表。
     */
    List<CatalogSkuView> listSkus(Long productId);
}
