package com.bytedance.ai.graph.catalog.api;

/**
 * 商品目录门面接口，定义对外可调用的业务能力边界。
 */
public interface CatalogInventoryFacade {

    default void decreaseStock(Long productId, int quantity) {
        decreaseStock(productId, null, quantity);
    }

    void decreaseStock(Long productId, Long skuId, int quantity);
}
