package com.bytedance.ai.graph.cartmanage.application;

import com.bytedance.ai.graph.cartmanage.StockResult;

/**
 * Inventory probe used by cart_manage_workflow before approving a quantity change.
 */
public interface InventoryQueryService {

    StockResult checkStock(String productId, String skuId, int requestedQuantity);
}
