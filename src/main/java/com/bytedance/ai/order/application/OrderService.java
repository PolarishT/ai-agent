package com.bytedance.ai.order.application;

import com.bytedance.ai.cart.api.CartCommandFacade;
import com.bytedance.ai.cart.api.CartItemView;
import com.bytedance.ai.cart.api.CartQueryFacade;
import com.bytedance.ai.cart.api.CartView;
import com.bytedance.ai.catalog.api.CatalogInventoryFacade;
import com.bytedance.ai.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import com.bytedance.ai.order.api.DeliveryAddressView;
import com.bytedance.ai.order.api.OrderCommandFacade;
import com.bytedance.ai.order.api.OrderItemView;
import com.bytedance.ai.order.api.OrderQueryFacade;
import com.bytedance.ai.order.api.OrderView;
import com.bytedance.ai.order.api.PlaceOrderResult;
import com.bytedance.ai.order.api.PriceChangeView;
import com.bytedance.ai.order.persistence.CustomerOrderRecord;
import com.bytedance.ai.order.persistence.CustomerOrderRepository;
import com.bytedance.ai.order.persistence.DeliveryAddressRecord;
import com.bytedance.ai.order.persistence.DeliveryAddressRepository;
import com.bytedance.ai.order.persistence.OrderItemRecord;
import com.bytedance.ai.order.persistence.OrderItemRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
        for (CartItemView item : placedCart.items()) {
            inventoryFacade.decreaseStock(item.spuId(), item.quantity());
        }
        CustomerOrderRecord order = orderRepository.save(
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
        for (CartItemView item : placedCart.items()) {
            itemRepository.save(
                    order.id(),
                    item.spuId(),
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
        CatalogSpuView spu = catalogQueryFacade.getSpu(item.spuId());
        BigDecimal current = displayPrice(spu);
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

    private BigDecimal displayPrice(CatalogSpuView spu) {
        return spu.priceMin() != null ? spu.priceMin() : spu.priceMax();
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
