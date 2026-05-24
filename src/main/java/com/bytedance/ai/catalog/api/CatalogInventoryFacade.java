package com.bytedance.ai.catalog.api;

public interface CatalogInventoryFacade {

    void decreaseStock(Long spuId, int quantity);
}
