package com.bytedance.ai.graph.cartmanage.subgraph.support;

import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartView;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

public class CartItemLookup {

    public boolean hasTarget(Integer itemIndex, String productName, String productId, String skuId) {
        return itemIndex != null && itemIndex >= 1
                || StringUtils.hasText(productName)
                || StringUtils.hasText(productId)
                || StringUtils.hasText(skuId);
    }

    public CartItemView find(
            CartView cart,
            Integer itemIndex,
            String productName,
            String productId,
            String skuId
    ) {
        if (cart == null || cart.items() == null || cart.items().isEmpty()) {
            return null;
        }
        List<CartItemView> items = cart.items();
        if (itemIndex != null && itemIndex >= 1 && itemIndex <= items.size()) {
            return items.get(itemIndex - 1);
        }
        if (StringUtils.hasText(productId) && StringUtils.hasText(skuId)) {
            Optional<CartItemView> matched = items.stream()
                    .filter(item -> matchesProductId(item, productId) && matchesSkuId(item, skuId))
                    .findFirst();
            if (matched.isPresent()) {
                return matched.get();
            }
        }
        if (StringUtils.hasText(skuId)) {
            Optional<CartItemView> matched = items.stream()
                    .filter(item -> matchesSkuId(item, skuId))
                    .findFirst();
            if (matched.isPresent()) {
                return matched.get();
            }
        }
        if (StringUtils.hasText(productId)) {
            Optional<CartItemView> matched = items.stream()
                    .filter(item -> matchesProductId(item, productId))
                    .findFirst();
            if (matched.isPresent()) {
                return matched.get();
            }
        }
        if (StringUtils.hasText(productName)) {
            String normalizedName = CartGraphStateSupport.normalizeMatchText(productName);
            return items.stream()
                    .filter(item -> CartGraphStateSupport.normalizeMatchText(item.title()).contains(normalizedName))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private boolean matchesProductId(CartItemView item, String productId) {
        if (!StringUtils.hasText(productId) || item == null) {
            return false;
        }
        String normalized = productId.trim();
        return item.spuId() != null && normalized.equals(String.valueOf(item.spuId()))
                || StringUtils.hasText(item.externalRef()) && item.externalRef().contains(normalized);
    }

    private boolean matchesSkuId(CartItemView item, String skuId) {
        if (!StringUtils.hasText(skuId) || item == null) {
            return false;
        }
        return StringUtils.hasText(item.externalRef()) && item.externalRef().contains(skuId.trim());
    }
}
