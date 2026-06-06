package com.bytedance.ai.graph.ordermanage.application;

import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartView;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单购物车快照服务，生成下单前的商品、价格和库存快照。
 */
@Component
public class OrderCartSnapshotService {

    public Map<String, Object> snapshot(CartView cart) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("cartId", cart.cartId());
        snapshot.put("currency", cart.currency());
        snapshot.put("subtotalAmount", amount(cart));
        snapshot.put("itemCount", cart.itemCount());
        snapshot.put("items", sortedItems(cart).stream().map(this::itemMap).toList());
        return snapshot;
    }

    public BigDecimal amount(CartView cart) {
        if (cart == null || cart.items() == null) {
            return BigDecimal.ZERO;
        }
        return cart.items().stream()
                .map(item -> item.lineAmount() != null
                        ? item.lineAmount()
                        : nullToZero(item.unitPrice()).multiply(BigDecimal.valueOf(item.quantity() == null ? 0 : item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public String hash(CartView cart) {
        return hash(snapshot(cart));
    }

    public String hash(Map<String, Object> snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical(snapshot).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("cart snapshot hash failed", exception);
        }
    }

    public List<CartItemView> sortedItems(CartView cart) {
        if (cart == null || cart.items() == null) {
            return List.of();
        }
        return cart.items().stream()
                .sorted(Comparator
                        .comparing((CartItemView item) -> item.itemId() == null ? Long.MAX_VALUE : item.itemId())
                        .thenComparing(item -> item.spuId() == null ? Long.MAX_VALUE : item.spuId())
                        .thenComparing(item -> item.skuId() == null ? Long.MAX_VALUE : item.skuId())
                        .thenComparing(item -> item.externalRef() == null ? "" : item.externalRef()))
                .toList();
    }

    private Map<String, Object> itemMap(CartItemView item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("itemId", item.itemId());
        map.put("spuId", item.spuId());
        map.put("skuId", item.skuId());
        map.put("externalRef", item.externalRef());
        map.put("title", item.title());
        map.put("quantity", item.quantity());
        map.put("unitPrice", item.unitPrice());
        map.put("lineAmount", item.lineAmount());
        return map;
    }

    private String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder("{");
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> builder
                            .append(entry.getKey())
                            .append('=')
                            .append(canonical(entry.getValue()))
                            .append(';'));
            return builder.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder("[");
            for (Object item : iterable) {
                builder.append(canonical(item)).append(';');
            }
            return builder.append(']').toString();
        }
        return String.valueOf(value);
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
