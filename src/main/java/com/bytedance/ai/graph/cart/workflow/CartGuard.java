package com.bytedance.ai.graph.cart.workflow;

import com.bytedance.ai.graph.cart.api.CartState;
import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 购物车状态机守卫，校验状态迁移所需的业务条件。
 */
@Component
public class CartGuard {

    private static final Logger log = LoggerFactory.getLogger(CartGuard.class);

    private final CatalogQueryFacade catalogQueryFacade;

    public CartGuard(CatalogQueryFacade catalogQueryFacade) {
        this.catalogQueryFacade = catalogQueryFacade;
    }

    public CatalogProductView validate(CartEvent event, CartState fromState, CartCommand command) {
        if (event == CartEvent.CONFIRM_ADD || event == CartEvent.UPDATE_QTY || event == CartEvent.PROPOSE_ITEM) {
            CatalogProductView product = resolveProduct(command);
            int quantity = command.quantity() == null || command.quantity() <= 0 ? 1 : command.quantity();
            if (!"ACTIVE".equals(product.status())) {
                throw new CartWorkflowException("商品已下架，无法加入购物车");
            }
            if (product.totalStock() == null || product.totalStock() < quantity) {
                throw new CartWorkflowException("库存不足，当前无法加入购物车");
            }
            CatalogSkuView sku = resolveSku(product, command.skuId());
            validateSku(sku, command.skuId(), quantity);
            BigDecimal currentPrice = displayPrice(product, sku);
            if (command.expectedUnitPrice() != null && currentPrice != null) {
                int compareToResult = command.expectedUnitPrice().compareTo(currentPrice);
                log.atInfo()
                        .addKeyValue("cart.product_id", command.spuId())
                        .addKeyValue("cart.sku_id", command.skuId())
                        .addKeyValue("cart.expected_price", command.expectedUnitPrice())
                        .addKeyValue("cart.current_price", currentPrice)
                        .addKeyValue("cart.expected_price_scale", command.expectedUnitPrice().scale())
                        .addKeyValue("cart.current_price_scale", currentPrice.scale())
                        .addKeyValue("cart.price_compare_to", compareToResult)
                        .addKeyValue("cart.price_source", "PRODUCT_CURRENT")
                        .log("Cart price guard compared expected price with current catalog price");
                if (compareToResult != 0) {
                    throw new CartWorkflowException("商品价格已变化，请二次确认后再加入购物车");
                }
            }
            return product;
        }
        if (event == CartEvent.CHECKOUT && fromState == CartState.CHECKING_OUT
                && (command.shippingAddress() == null || command.shippingAddress().isEmpty())) {
            throw new CartWorkflowException("收货地址缺失，请先补充有效地址");
        }
        return null;
    }

    private CatalogProductView resolveProduct(CartCommand command) {
        if (command.spuId() != null) {
            return catalogQueryFacade.getProduct(command.spuId());
        }
        if (StringUtils.hasText(command.externalRef())) {
            // catalog_product DDL 已无 external_ref 列；生产路径（GraphCartCommandAdapter）总是传
            // spuId（= catalog_product.id），这里只兜底。留 warn 让任何手工/测试侧只传 externalRef
            // 的 caller 能在日志里立刻看到原因。
            log.atWarn()
                    .addKeyValue("event.name", "cart_guard.resolve_product.external_ref_unsupported")
                    .addKeyValue("cart.external_ref", command.externalRef())
                    .log("CartCommand carried externalRef without spuId; catalog lookup no longer "
                            + "supports external_ref. Please populate spuId(productId) from upstream.");
            throw new CartWorkflowException("缺少商品信息，请先选择要加入购物车的商品");
        }
        throw new CartWorkflowException("缺少商品信息，请先选择要加入购物车的商品");
    }

    private CatalogSkuView resolveSku(CatalogProductView product, Long skuId) {
        if (skuId == null) {
            return null;
        }
        return product.skus() == null ? null : product.skus().stream()
                .filter(candidate -> candidate != null && skuId.equals(candidate.id()))
                .findFirst()
                .orElse(null);
    }

    private void validateSku(CatalogSkuView sku, Long skuId, int quantity) {
        if (skuId == null) {
            return;
        }
        if (sku == null) {
            throw new CartWorkflowException("SKU 不属于该商品，无法加入购物车");
        }
        if (!"ACTIVE".equals(sku.status())) {
            throw new CartWorkflowException("SKU 已下架，无法加入购物车");
        }
        if (sku.stock() == null || sku.stock() < quantity) {
            throw new CartWorkflowException("SKU 库存不足，当前无法加入购物车");
        }
    }

    private BigDecimal displayPrice(CatalogProductView product, CatalogSkuView sku) {
        if (sku != null && sku.price() != null) {
            return sku.price();
        }
        return product.priceMin() != null ? product.priceMin() : product.priceMax();
    }
}
