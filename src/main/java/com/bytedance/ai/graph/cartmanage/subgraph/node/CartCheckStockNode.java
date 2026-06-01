package com.bytedance.ai.graph.cartmanage.subgraph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.cartmanage.StockResult;
import com.bytedance.ai.graph.cartmanage.application.InventoryQueryService;
import com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys;
import com.bytedance.ai.graph.cartmanage.subgraph.CartWorkflowStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 购物车管理子图节点，在执行变更前校验目标商品库存是否满足需求。
 */
public class CartCheckStockNode {

    private static final Logger log = LoggerFactory.getLogger(CartCheckStockNode.class);

    private final InventoryQueryService inventoryQueryService;

    public CartCheckStockNode(InventoryQueryService inventoryQueryService) {
        this.inventoryQueryService = inventoryQueryService;
    }

    public Map<String, Object> apply(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        String productId = state.value(CartGraphStateKeys.PRODUCT_ID, "");
        String skuId = state.value(CartGraphStateKeys.SKU_ID, "");
        int quantity = state.value(CartGraphStateKeys.QUANTITY, 1);

        log.info("Cart check stock start: productId={}, skuId={}, quantity={}", productId, skuId, quantity);
        StockResult stock = inventoryQueryService.checkStock(productId, skuId, quantity);
        updates.put(CartGraphStateKeys.STOCK_RESULT, stock);

        if (!stock.available()) {
            String message = String.format(Locale.ROOT, "「%s」库存不足，当前最多可购买 %d 件。",
                    state.value(CartGraphStateKeys.PRODUCT_NAME, "该商品"),
                    Math.max(stock.availableQty(), 0));
            updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.STOCK_NOT_ENOUGH.name());
            updates.put(CartGraphStateKeys.NODE_MESSAGE, message);
            updates.put(CartGraphStateKeys.NEED_USER_INPUT, false);
        }

        log.info("Cart check stock done: productId={}, skuId={}, quantity={}, available={}, availableQty={}, status={}",
                productId,
                skuId,
                quantity,
                stock.available(),
                stock.availableQty(),
                updates.get(CartGraphStateKeys.WORKFLOW_STATUS));
        return updates;
    }

    public String routeAfter(OverAllState state) {
        Optional<StockResult> stock = state.value(CartGraphStateKeys.STOCK_RESULT, StockResult.class);
        String route;
        if (stock.isEmpty() || !stock.get().available()) {
            route = "OUT_OF_STOCK";
        } else {
            route = "IN_STOCK";
        }
        log.info("Cart route after check stock: hasStockResult={}, available={}, route={}",
                stock.isPresent(), stock.map(StockResult::available).orElse(false), route);
        return route;
    }
}
