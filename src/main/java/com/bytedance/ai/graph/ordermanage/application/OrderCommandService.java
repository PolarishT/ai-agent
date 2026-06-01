package com.bytedance.ai.graph.ordermanage.application;

import com.bytedance.ai.graph.ordermanage.persistence.PendingOrderActionRepository;
import com.bytedance.ai.graph.ordermanage.persistence.PendingOrderActionRecord;
import com.bytedance.ai.graph.ordermanage.persistence.MockOrderRepository;
import com.bytedance.ai.graph.ordermanage.OrderManageStatus;
import com.bytedance.ai.graph.ordermanage.OrderCreateResult;
import com.bytedance.ai.graph.ordermanage.AddressSnapshot;
import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartQueryFacade;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.catalog.api.CatalogInventoryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.cartmanage.application.CartCommandService;
import com.bytedance.ai.graph.cartmanage.CartMutationResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderCommandService {

    private final PendingOrderActionRepository pendingRepository;
    private final CartQueryFacade cartQueryFacade;
    private final CatalogQueryFacade catalogQueryFacade;
    private final CatalogInventoryFacade inventoryFacade;
    private final CartCommandService cartCommandService;
    private final MockOrderRepository mockOrderRepository;
    private final OrderCartSnapshotService snapshotService;

    public OrderCommandService(
            PendingOrderActionRepository pendingRepository,
            CartQueryFacade cartQueryFacade,
            CatalogQueryFacade catalogQueryFacade,
            CatalogInventoryFacade inventoryFacade,
            CartCommandService cartCommandService,
            MockOrderRepository mockOrderRepository,
            OrderCartSnapshotService snapshotService
    ) {
        this.pendingRepository = pendingRepository;
        this.cartQueryFacade = cartQueryFacade;
        this.catalogQueryFacade = catalogQueryFacade;
        this.inventoryFacade = inventoryFacade;
        this.cartCommandService = cartCommandService;
        this.mockOrderRepository = mockOrderRepository;
        this.snapshotService = snapshotService;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderCreateResult createMockOrderFromPending(String userId, String conversationId, Long pendingId) {
        if (!pendingRepository.markCreatingIfWaitingConfirmation(pendingId)) {
            return OrderCreateResult.failure("当前没有等待确认的订单，不能重复创建订单。", OrderManageStatus.FAILED);
        }
        PendingOrderActionRecord pending = pendingRepository.findById(pendingId)
                .orElseThrow(() -> new IllegalStateException("pending order action not found: " + pendingId));
        if (pending.expireAt() != null && pending.expireAt().isBefore(LocalDateTime.now())) {
            pendingRepository.markExpired(pending.id());
            return OrderCreateResult.failure("本次下单确认已过期，请重新发送‘结算购物车’。", OrderManageStatus.EXPIRED);
        }
        AddressSnapshot address = AddressSnapshot.fromMap(pending.addressSnapshot());
        if (!StringUtils.hasText(address.receiverName())
                || !StringUtils.hasText(address.phone())
                || !StringUtils.hasText(address.addressText())) {
            pendingRepository.markFailed(pending.id(), "address incomplete");
            return OrderCreateResult.failure("请先提供完整收货地址后再确认下单。", OrderManageStatus.FAILED);
        }

        CartView currentCart = cartQueryFacade.getActiveCart(userId, conversationId);
        if (currentCart == null || currentCart.items() == null || currentCart.items().isEmpty()) {
            pendingRepository.markFailed(pending.id(), "cart empty");
            return OrderCreateResult.failure("购物车内容已变化，请重新发送‘结算购物车’。", OrderManageStatus.FAILED);
        }
        String currentHash = snapshotService.hash(currentCart);
        if (!currentHash.equals(pending.cartSnapshotHash())) {
            pendingRepository.markFailed(pending.id(), "cart snapshot changed");
            return OrderCreateResult.failure("购物车内容已变化，请重新发送‘结算购物车’。", OrderManageStatus.FAILED);
        }

        String stockError = validateProductsAndDeductStock(currentCart);
        if (stockError != null) {
            pendingRepository.markFailed(pending.id(), stockError);
            return OrderCreateResult.failure("部分商品库存不足，本次订单已失效，请调整购物车后重新结算。", OrderManageStatus.FAILED);
        }

        String orderNo = nextOrderNo();
        mockOrderRepository.create(
                orderNo,
                userId,
                conversationId,
                Map.of("items", snapshotService.snapshot(currentCart).get("items")),
                pending.addressSnapshot(),
                snapshotService.amount(currentCart)
        );
        CartMutationResult clearResult = cartCommandService.clearCart(userId, conversationId);
        if (!clearResult.success()) {
            throw new IllegalStateException(clearResult.errorMessage());
        }
        pendingRepository.markCreated(pending.id(), orderNo);
        return OrderCreateResult.success(orderNo);
    }

    private String validateProductsAndDeductStock(CartView cart) {
        for (CartItemView item : snapshotService.sortedItems(cart)) {
            CatalogProductView product = catalogQueryFacade.getProduct(item.spuId());
            int quantity = item.quantity() == null ? 0 : item.quantity();
            if (!"ACTIVE".equals(product.status()) || product.totalStock() == null || product.totalStock() < quantity) {
                return "库存不足: " + item.title();
            }
            inventoryFacade.decreaseStock(item.spuId(), quantity);
        }
        return null;
    }

    private String nextOrderNo() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "ORD-" + timestamp + "-" + random;
    }
}
