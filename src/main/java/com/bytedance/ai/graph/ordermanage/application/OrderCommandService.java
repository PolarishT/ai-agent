package com.bytedance.ai.graph.ordermanage.application;

import com.bytedance.ai.graph.ordermanage.persistence.MockOrderRepository;
import com.bytedance.ai.graph.ordermanage.OrderManageStatus;
import com.bytedance.ai.graph.ordermanage.OrderCreateResult;
import com.bytedance.ai.graph.ordermanage.AddressSnapshot;
import com.bytedance.ai.graph.conversation.context.ConversationContextItemStatus;
import com.bytedance.ai.graph.conversation.context.ConversationContextManager;
import com.bytedance.ai.graph.conversation.context.ConversationRuntimeContext;
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

/**
 * 订单管理写入服务，负责创建模拟订单并记录待确认动作。
 */
@Service
public class OrderCommandService {

    private final ConversationContextManager conversationContextManager;
    private final CartQueryFacade cartQueryFacade;
    private final CatalogQueryFacade catalogQueryFacade;
    private final CatalogInventoryFacade inventoryFacade;
    private final CartCommandService cartCommandService;
    private final MockOrderRepository mockOrderRepository;
    private final OrderCartSnapshotService snapshotService;

    public OrderCommandService(
            ConversationContextManager conversationContextManager,
            CartQueryFacade cartQueryFacade,
            CatalogQueryFacade catalogQueryFacade,
            CatalogInventoryFacade inventoryFacade,
            CartCommandService cartCommandService,
            MockOrderRepository mockOrderRepository,
            OrderCartSnapshotService snapshotService
    ) {
        this.conversationContextManager = conversationContextManager;
        this.cartQueryFacade = cartQueryFacade;
        this.catalogQueryFacade = catalogQueryFacade;
        this.inventoryFacade = inventoryFacade;
        this.cartCommandService = cartCommandService;
        this.mockOrderRepository = mockOrderRepository;
        this.snapshotService = snapshotService;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderCreateResult createMockOrderFromContext(
            String userId,
            String conversationId,
            ConversationRuntimeContext.OrderContext orderContext
    ) {
        if (orderContext == null || orderContext.contextItemId() == null) {
            return OrderCreateResult.failure("当前没有等待确认的订单，不能重复创建订单。", OrderManageStatus.FAILED);
        }
        ConversationRuntimeContext.OrderContext creating = copyOrder(
                orderContext,
                OrderManageStatus.CREATING,
                null,
                null
        );
        if (!conversationContextManager.transitionOrderContextStatus(
                orderContext.contextItemId(),
                OrderManageStatus.WAITING_CONFIRMATION.name(),
                creating,
                ConversationContextItemStatus.ACTIVE
        )) {
            return OrderCreateResult.failure("当前没有等待确认的订单，不能重复创建订单。", OrderManageStatus.FAILED);
        }
        if (orderContext.expiresAt() != null && orderContext.expiresAt().isBefore(LocalDateTime.now())) {
            conversationContextManager.transitionOrderContextStatus(
                    orderContext.contextItemId(),
                    OrderManageStatus.CREATING.name(),
                    copyOrder(orderContext, OrderManageStatus.EXPIRED, "order context expired", null),
                    ConversationContextItemStatus.EXPIRED
            );
            return OrderCreateResult.failure("本次下单确认已过期，请重新发送‘结算购物车’。", OrderManageStatus.EXPIRED);
        }
        AddressSnapshot address = AddressSnapshot.fromMap(orderContext.addressSnapshot());
        if (!StringUtils.hasText(address.receiverName())
                || !StringUtils.hasText(address.phone())
                || !StringUtils.hasText(address.addressText())) {
            markOrderFailed(orderContext, "address incomplete");
            return OrderCreateResult.failure("请先提供完整收货地址后再确认下单。", OrderManageStatus.FAILED);
        }

        CartView currentCart = cartQueryFacade.getActiveCart(userId, conversationId);
        if (currentCart == null || currentCart.items() == null || currentCart.items().isEmpty()) {
            markOrderFailed(orderContext, "cart empty");
            return OrderCreateResult.failure("购物车内容已变化，请重新发送‘结算购物车’。", OrderManageStatus.FAILED);
        }
        String currentHash = snapshotService.hash(currentCart);
        if (!currentHash.equals(orderContext.cartSnapshotHash())) {
            markOrderFailed(orderContext, "cart snapshot changed");
            return OrderCreateResult.failure("购物车内容已变化，请重新发送‘结算购物车’。", OrderManageStatus.FAILED);
        }

        String stockError = validateProductsAndDeductStock(currentCart);
        if (stockError != null) {
            markOrderFailed(orderContext, stockError);
            return OrderCreateResult.failure("部分商品库存不足，本次订单已失效，请调整购物车后重新结算。", OrderManageStatus.FAILED);
        }

        String orderNo = nextOrderNo();
        mockOrderRepository.create(
                orderNo,
                userId,
                conversationId,
                Map.of("items", snapshotService.snapshot(currentCart).get("items")),
                orderContext.addressSnapshot(),
                snapshotService.amount(currentCart)
        );
        CartMutationResult clearResult = cartCommandService.clearCart(userId, conversationId);
        if (!clearResult.success()) {
            throw new IllegalStateException(clearResult.errorMessage());
        }
        conversationContextManager.transitionOrderContextStatus(
                orderContext.contextItemId(),
                OrderManageStatus.CREATING.name(),
                copyOrder(orderContext, OrderManageStatus.ORDER_CREATED, null, orderNo),
                ConversationContextItemStatus.COMPLETED
        );
        return OrderCreateResult.success(orderNo);
    }

    private void markOrderFailed(ConversationRuntimeContext.OrderContext orderContext, String reason) {
        conversationContextManager.transitionOrderContextStatus(
                orderContext.contextItemId(),
                OrderManageStatus.CREATING.name(),
                copyOrder(orderContext, OrderManageStatus.FAILED, reason, null),
                ConversationContextItemStatus.FAILED
        );
    }

    private ConversationRuntimeContext.OrderContext copyOrder(
            ConversationRuntimeContext.OrderContext orderContext,
            OrderManageStatus status,
            String failReason,
            String orderNo
    ) {
        return new ConversationRuntimeContext.OrderContext(
                orderContext.contextItemId(),
                orderContext.cartSnapshot(),
                orderContext.cartSnapshotHash(),
                orderContext.addressSnapshot(),
                orderContext.amountSnapshot(),
                status.name(),
                failReason,
                orderNo == null ? orderContext.orderNo() : orderNo,
                orderContext.expiresAt(),
                orderContext.payload()
        );
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
