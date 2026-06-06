package com.bytedance.ai.graph.order.application;

import com.bytedance.ai.graph.cart.api.CartCommandFacade;
import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartQueryFacade;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.catalog.api.CatalogInventoryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.order.api.DeliveryAddressView;
import com.bytedance.ai.graph.order.api.OrderCommandFacade;
import com.bytedance.ai.graph.order.api.OrderItemView;
import com.bytedance.ai.graph.order.api.OrderQueryFacade;
import com.bytedance.ai.graph.order.api.OrderView;
import com.bytedance.ai.graph.order.api.PlaceOrderResult;
import com.bytedance.ai.graph.order.api.PriceChangeView;
import com.bytedance.ai.graph.order.persistence.CustomerOrderRecord;
import com.bytedance.ai.graph.order.persistence.CustomerOrderRepository;
import com.bytedance.ai.graph.order.persistence.DeliveryAddressRecord;
import com.bytedance.ai.graph.order.persistence.DeliveryAddressRepository;
import com.bytedance.ai.graph.order.persistence.OrderItemRecord;
import com.bytedance.ai.graph.order.persistence.OrderItemRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 订单应用服务，负责订单创建、查询和价格变更提示。
 */
@Service
public class OrderService implements OrderCommandFacade, OrderQueryFacade {

    private final CartQueryFacade cartQueryFacade;
    private final CartCommandFacade cartCommandFacade;
    private final CatalogQueryFacade catalogQueryFacade;
    private final CatalogInventoryFacade inventoryFacade;
    private final DeliveryAddressRepository addressRepository;
    private final CustomerOrderRepository orderRepository;
    private final OrderItemRepository itemRepository;

    public OrderService(
            CartQueryFacade cartQueryFacade,
            CartCommandFacade cartCommandFacade,
            CatalogQueryFacade catalogQueryFacade,
            CatalogInventoryFacade inventoryFacade,
            DeliveryAddressRepository addressRepository,
            CustomerOrderRepository orderRepository,
            OrderItemRepository itemRepository
    ) {
        this.cartQueryFacade = cartQueryFacade;
        this.cartCommandFacade = cartCommandFacade;
        this.catalogQueryFacade = catalogQueryFacade;
        this.inventoryFacade = inventoryFacade;
        this.addressRepository = addressRepository;
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
    }

    /**
     * 库存扣减顺序：按 (spuId, skuId) 升序。让所有并发下单事务以一致的全局顺序获取
     * catalog_sku / catalog_product 行锁，消除两笔订单反序扣减引发的 AB-BA 死锁。
     */
    private static final Comparator<CartItemView> STOCK_LOCK_ORDER = Comparator
            .comparing(CartItemView::spuId, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(CartItemView::skuId, Comparator.nullsLast(Comparator.naturalOrder()));

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public PlaceOrderResult placeOrder(String userId, String conversationId, Map<String, Object> address, boolean confirmPriceChange) {
        CartView cart = cartQueryFacade.getActiveCart(userId, conversationId);
        if (cart.items().isEmpty()) {
            throw new IllegalStateException("购物车为空，无法下单");
        }
        List<PriceChangeView> priceChanges = detectPriceChanges(cart);
        if (!priceChanges.isEmpty() && !confirmPriceChange) {
            return PlaceOrderResult.confirmationRequired(priceChanges);
        }

        DeliveryAddressRecord deliveryAddress = resolveAddress(userId, address);
        Map<String, Object> addressMap = toAddressMap(deliveryAddress);
        CartView placedCart = cartCommandFacade.checkout(userId, conversationId, addressMap);
        // 按统一锁顺序扣减库存，避免并发下单的 AB-BA 死锁（见 STOCK_LOCK_ORDER）。
        for (CartItemView item : placedCart.items().stream().sorted(STOCK_LOCK_ORDER).toList()) {
            inventoryFacade.decreaseStock(item.spuId(), item.skuId(), item.quantity());
        }
        CustomerOrderRecord order;
        try {
            order = orderRepository.save(
                    placedCart.cartId(),
                    userId,
                    conversationId,
                    placedCart.currency(),
                    placedCart.subtotalAmount(),
                    placedCart.itemCount(),
                    deliveryAddress.id(),
                    addressMap,
                    priceChanges.stream().map(this::priceChangeMap).toList()
            );
        } catch (DuplicateKeyException duplicate) {
            // 命中唯一索引 uq_customer_order_cart_id：同一购物车已生成订单（并发 / 重复提交）。
            // 抛出后整笔事务回滚，前面的库存扣减与购物车结算一并撤销，杜绝重复下单与重复扣库存。
            throw new IllegalStateException("该购物车已生成订单，请勿重复下单", duplicate);
        }
        for (CartItemView item : placedCart.items()) {
            itemRepository.save(
                    order.id(),
                    item.spuId(),
                    item.skuId(),
                    item.externalRef(),
                    item.title(),
                    item.brand(),
                    item.imageUrl(),
                    item.quantity(),
                    item.unitPrice(),
                    item.lineAmount()
            );
        }
        return PlaceOrderResult.placed(toView(order, itemRepository.findByOrderId(order.id())));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderView getOrder(String orderId) {
        CustomerOrderRecord order = orderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderId));
        return toView(order, itemRepository.findByOrderId(order.id()));
    }

    private List<PriceChangeView> detectPriceChanges(CartView cart) {
        return cart.items().stream()
                .map(this::detectPriceChange)
                .filter(change -> change != null)
                .toList();
    }

    private PriceChangeView detectPriceChange(CartItemView item) {
        CatalogProductView product = catalogQueryFacade.getProduct(item.spuId());
        BigDecimal current = displayPrice(product, item.skuId());
        if (item.unitPrice() == null || current == null || item.unitPrice().compareTo(current) == 0) {
            return null;
        }
        return new PriceChangeView(item.spuId(), item.externalRef(), item.title(), item.unitPrice(), current);
    }

    private DeliveryAddressRecord resolveAddress(String userId, Map<String, Object> address) {
        if (address != null && !address.isEmpty()) {
            return addressRepository.save(userId, address, false);
        }
        return addressRepository.findDefaultByUserId(userId)
                .orElseGet(() -> addressRepository.saveDefaultIfAbsent(userId));
    }

    private BigDecimal displayPrice(CatalogProductView product, Long skuId) {
        CatalogSkuView sku = selectedSku(product, skuId);
        if (sku != null && sku.price() != null) {
            return sku.price();
        }
        return product.priceMin() != null ? product.priceMin() : product.priceMax();
    }

    private CatalogSkuView selectedSku(CatalogProductView product, Long skuId) {
        if (skuId == null || product.skus() == null) {
            return null;
        }
        return product.skus().stream()
                .filter(sku -> sku != null && skuId.equals(sku.id()))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> toAddressMap(DeliveryAddressRecord address) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", address.id());
        map.put("receiverName", address.receiverName());
        map.put("phone", address.phone());
        map.put("province", address.province());
        map.put("city", address.city());
        map.put("district", address.district());
        map.put("detail", address.detail());
        map.put("postalCode", address.postalCode());
        return map;
    }

    private Map<String, Object> priceChangeMap(PriceChangeView change) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("spuId", change.spuId());
        map.put("externalRef", change.externalRef());
        map.put("title", change.title());
        map.put("cartUnitPrice", change.cartUnitPrice());
        map.put("currentUnitPrice", change.currentUnitPrice());
        return map;
    }

    private OrderView toView(CustomerOrderRecord order, List<OrderItemRecord> items) {
        return new OrderView(
                order.orderId(),
                order.cartId(),
                order.userId(),
                order.conversationId(),
                order.status(),
                order.currency(),
                order.subtotalAmount(),
                order.itemCount(),
                toAddressView(order.deliveryAddressId(), order.deliveryAddress()),
                toPriceChanges(order.priceChanges()),
                items.stream().map(this::toItemView).toList(),
                order.placedAt()
        );
    }

    private DeliveryAddressView toAddressView(Long addressId, Map<String, Object> raw) {
        return new DeliveryAddressView(
                addressId,
                text(raw, "receiverName"),
                text(raw, "phone"),
                text(raw, "province"),
                text(raw, "city"),
                text(raw, "district"),
                text(raw, "detail"),
                text(raw, "postalCode")
        );
    }

    private List<PriceChangeView> toPriceChanges(List<Map<String, Object>> raw) {
        return raw.stream()
                .map(map -> new PriceChangeView(
                        longValue(map.get("spuId")),
                        text(map, "externalRef"),
                        text(map, "title"),
                        decimalValue(map.get("cartUnitPrice")),
                        decimalValue(map.get("currentUnitPrice"))
                ))
                .toList();
    }

    private OrderItemView toItemView(OrderItemRecord item) {
        return new OrderItemView(
                item.id(),
                item.spuId(),
                item.skuId(),
                item.externalRef(),
                item.title(),
                item.brand(),
                item.imageUrl(),
                item.quantity(),
                item.unitPrice(),
                item.lineAmount()
        );
    }

    private String text(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Long.parseLong(text);
        }
        return null;
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return new BigDecimal(text);
        }
        return null;
    }
}
