package com.bytedance.ai.graph.cartmanage.adapter;

import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.cartmanage.application.InventoryQueryService;
import com.bytedance.ai.graph.cartmanage.StockResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.List;

/**
 * Real inventory probe for cart management, backed by {@code catalog_product} stock.
 */
@Service
public class CatalogInventoryQueryService implements InventoryQueryService {

    private static final String ACTIVE = "ACTIVE";

    private final CatalogQueryFacade catalogQueryFacade;

    public CatalogInventoryQueryService(CatalogQueryFacade catalogQueryFacade) {
        this.catalogQueryFacade = catalogQueryFacade;
    }

    @Override
    public StockResult checkStock(String productId, String skuId, int requestedQuantity) {
        if (requestedQuantity <= 0) {
            return StockResult.outOfStock(productId, skuId, 0);
        }

        Long productPrimaryId = parseId(productId);
        if (productPrimaryId == null) {
            return StockResult.outOfStock(productId, skuId, 0);
        }

        CatalogProductView product;
        try {
            product = catalogQueryFacade.getProduct(productPrimaryId);
        } catch (RuntimeException ignored) {
            return StockResult.outOfStock(productId, skuId, 0);
        }

        if (product == null || !ACTIVE.equalsIgnoreCase(nullToEmpty(product.status()))) {
            return StockResult.outOfStock(productId, skuId, 0);
        }

        Long skuPrimaryId = parseId(skuId);
        if (skuPrimaryId != null) {
            return checkSkuStock(productId, skuId, requestedQuantity, product.skus(), skuPrimaryId);
        }

        int availableQty = product.totalStock() == null ? 0 : product.totalStock();
        return availableQty >= requestedQuantity
                ? StockResult.inStock(productId, skuId, availableQty)
                : StockResult.outOfStock(productId, skuId, availableQty);
    }

    private StockResult checkSkuStock(
            String productId,
            String skuId,
            int requestedQuantity,
            List<CatalogSkuView> skus,
            Long skuPrimaryId
    ) {
        if (skus == null || skus.isEmpty()) {
            return StockResult.outOfStock(productId, skuId, 0);
        }
        return skus.stream()
                .filter(sku -> sku != null && skuPrimaryId.equals(sku.id()))
                .findFirst()
                .map(sku -> {
                    int availableQty = sku.stock() == null ? 0 : sku.stock();
                    boolean active = ACTIVE.equalsIgnoreCase(nullToEmpty(sku.status()));
                    return active && availableQty >= requestedQuantity
                            ? StockResult.inStock(productId, skuId, availableQty)
                            : StockResult.outOfStock(productId, skuId, availableQty);
                })
                .orElseGet(() -> StockResult.outOfStock(productId, skuId, 0));
    }

    private Long parseId(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
