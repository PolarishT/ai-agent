package com.bytedance.ai.graph.cart.application;

import com.bytedance.ai.graph.cart.api.CartCommandFacade;
import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartQueryFacade;
import com.bytedance.ai.graph.cart.api.CartState;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.cart.persistence.CartItemRecord;
import com.bytedance.ai.graph.cart.persistence.CartItemRepository;
import com.bytedance.ai.graph.cart.persistence.ShoppingCartRecord;
import com.bytedance.ai.graph.cart.persistence.ShoppingCartRepository;
import com.bytedance.ai.graph.cart.workflow.CartCommand;
import com.bytedance.ai.graph.cart.workflow.CartEvent;
import com.bytedance.ai.graph.cart.workflow.CartGuard;
import com.bytedance.ai.graph.cart.workflow.CartStateMachineFactory;
import com.bytedance.ai.graph.cart.workflow.CartTransitionAuditService;
import com.bytedance.ai.graph.cart.workflow.CartWorkflowException;
import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 购物车应用服务，提供购物车查询和变更能力。
 */
@Service
public class ShoppingCartService implements CartCommandFacade, CartQueryFacade {

    private final ShoppingCartRepository cartRepository;
    private final CartItemRepository itemRepository;
    private final CartStateMachineFactory stateMachineFactory;
    private final CartGuard guard;
    private final CartTransitionAuditService auditService;

    public ShoppingCartService(
            ShoppingCartRepository cartRepository,
            CartItemRepository itemRepository,
            CartStateMachineFactory stateMachineFactory,
            CartGuard guard,
            CartTransitionAuditService auditService
    ) {
        this.cartRepository = cartRepository;
        this.itemRepository = itemRepository;
        this.stateMachineFactory = stateMachineFactory;
        this.guard = guard;
        this.auditService = auditService;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public CartView proposeItem(String userId, String conversationId, Long spuId, String externalRef, Integer quantity) {
        ShoppingCartRecord cart = findOrCreate(userId, conversationId);
        CartCommand command = CartCommand.of(userId, conversationId, spuId, externalRef, quantity);
        transition(cart, CartEvent.PROPOSE_ITEM, command);
        return toView(refresh(cart.id()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public CartView addItem(
            String userId,
            String conversationId,
            Long spuId,
            String externalRef,
            Integer quantity,
            BigDecimal expectedUnitPrice
    ) {
        ShoppingCartRecord cart = findOrCreate(userId, conversationId);
        CartCommand command = new CartCommand(
                userId,
                conversationId,
                spuId,
                externalRef,
                quantity,
                expectedUnitPrice,
                Map.of(),
                "agent",
                null,
                null,
                Map.of()
        );
        CatalogProductView product = transition(cart, CartEvent.CONFIRM_ADD, command);
        int effectiveQuantity = effectiveQuantity(quantity);
        itemRepository.upsertActive(
                cart.id(),
                product.id(),
                // catalog_product 已无 external_ref 列；沿用全代码库的兼容约定填 productId 字符串，
                // 保证 cart_item.external_ref 非空且与 spu_id 指向同一商品。
                String.valueOf(product.id()),
                product.title(),
                product.brand(),
                firstImage(product),
                effectiveQuantity,
                displayPrice(product),
                product.totalStock()
        );
        recomputeTotals(cart.id());
        return toView(refresh(cart.id()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public CartView removeItem(String userId, String conversationId, Long itemId, Long spuId, String externalRef) {
        ShoppingCartRecord cart = findOrCreate(userId, conversationId);
        CartCommand command = CartCommand.of(userId, conversationId, spuId, externalRef, null);
        transition(cart, CartEvent.REMOVE, command);
        CartItemRecord item = itemRepository.findActive(cart.id(), itemId, spuId, externalRef)
                .orElseThrow(() -> new CartWorkflowException("购物车中未找到该商品"));
        itemRepository.markRemoved(item.id());
        recomputeTotals(cart.id());
        return toView(refresh(cart.id()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public CartView updateQuantity(String userId, String conversationId, Long itemId, Long spuId, String externalRef, Integer quantity) {
        ShoppingCartRecord cart = findOrCreate(userId, conversationId);
        int effectiveQuantity = effectiveQuantity(quantity);
        CartItemRecord item = itemRepository.findActive(cart.id(), itemId, spuId, externalRef)
                .orElseThrow(() -> new CartWorkflowException("购物车中未找到该商品"));
        CartCommand command = CartCommand.of(userId, conversationId, item.spuId(), item.externalRef(), effectiveQuantity);
        transition(cart, CartEvent.UPDATE_QTY, command);
        itemRepository.updateQuantity(item.id(), effectiveQuantity);
        recomputeTotals(cart.id());
        return toView(refresh(cart.id()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public CartView checkout(String userId, String conversationId, Map<String, Object> shippingAddress) {
        ShoppingCartRecord cart = findOrCreate(userId, conversationId);
        if (itemRepository.findActiveByCartId(cart.id()).isEmpty()) {
            throw new CartWorkflowException("购物车为空，无法结算");
        }
        CartCommand command = new CartCommand(
                userId,
                conversationId,
                null,
                null,
                null,
                null,
                shippingAddress,
                "agent",
                null,
                null,
                Map.of()
        );
        transition(cart, CartEvent.CHECKOUT, command);
        ShoppingCartRecord checkingOut = refresh(cart.id());
        if (shippingAddress != null && !shippingAddress.isEmpty()) {
            cartRepository.updateShippingAddress(cart.id(), shippingAddress);
        }
        transition(checkingOut, CartEvent.CHECKOUT, command);
        return toView(refresh(cart.id()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public CartView cancel(String userId, String conversationId) {
        ShoppingCartRecord cart = findOrCreate(userId, conversationId);
        transition(cart, CartEvent.CANCEL, CartCommand.of(userId, conversationId, null, null, null));
        return toView(refresh(cart.id()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public CartView getActiveCart(String userId, String conversationId) {
        ShoppingCartRecord cart = findOrCreate(userId, conversationId);
        return toView(cart);
    }

    private ShoppingCartRecord findOrCreate(String userId, String conversationId) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(conversationId)) {
            throw new CartWorkflowException("缺少 userId 或 conversationId");
        }
        return findCurrentUserCart(userId, conversationId)
                .orElseGet(() -> cartRepository.create(userId, conversationId));
    }

    private Optional<ShoppingCartRecord> findCurrentUserCart(String userId, String conversationId) {
        Optional<ShoppingCartRecord> scopedCart = cartRepository.findLatestActive(userId, conversationId);
        if (scopedCart.isPresent() && hasActiveItems(scopedCart.get())) {
            return scopedCart;
        }
        Optional<ShoppingCartRecord> userCart = cartRepository.findLatestActiveWithItemsByUser(userId);
        return userCart.isPresent() ? userCart : scopedCart;
    }

    private boolean hasActiveItems(ShoppingCartRecord cart) {
        return cart != null && !itemRepository.findActiveByCartId(cart.id()).isEmpty();
    }

    private CatalogProductView transition(ShoppingCartRecord cart, CartEvent event, CartCommand command) {
        CartState fromState = cart.state();
        CatalogProductView guardedProduct = null;
        try {
            guardedProduct = guard.validate(event, fromState, command);
            StateMachine<CartState, CartEvent> stateMachine = stateMachineFactory.create(fromState);
            stateMachine.start();
            try {
                boolean accepted = stateMachine.sendEvent(MessageBuilder.withPayload(event).build());
                if (!accepted) {
                    throw new CartWorkflowException("非法购物车状态跃迁: currentState=" + fromState + ", event=" + event);
                }
                CartState toState = stateMachine.getState().getId();
                cartRepository.updateState(cart.id(), toState);
                auditService.record(cart, fromState, toState, event, command, true, null, null);
                return guardedProduct;
            } finally {
                stateMachine.stop();
            }
        } catch (RuntimeException exception) {
            auditService.record(cart, fromState, fromState, event, command, false, "GUARD_OR_TRANSITION_FAILED", exception.getMessage());
            throw exception;
        }
    }

    private void recomputeTotals(Long cartId) {
        List<CartItemRecord> items = itemRepository.findActiveByCartId(cartId);
        BigDecimal subtotal = BigDecimal.ZERO;
        int itemCount = 0;
        for (CartItemRecord item : items) {
            itemCount += item.quantity();
            if (item.unitPrice() != null) {
                subtotal = subtotal.add(item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())));
            }
        }
        cartRepository.updateTotals(cartId, subtotal, itemCount);
    }

    private ShoppingCartRecord refresh(Long cartId) {
        return cartRepository.findById(cartId).orElseThrow(() -> new CartWorkflowException("购物车不存在"));
    }

    private CartView toView(ShoppingCartRecord cart) {
        List<CartItemView> items = itemRepository.findActiveByCartId(cart.id()).stream()
                .map(this::toItemView)
                .toList();
        return new CartView(
                cart.cartId(),
                cart.userId(),
                cart.conversationId(),
                cart.state(),
                cart.currency(),
                cart.subtotalAmount(),
                cart.itemCount(),
                cart.shippingAddress(),
                items
        );
    }

    private CartItemView toItemView(CartItemRecord item) {
        BigDecimal lineAmount = item.unitPrice() == null
                ? null
                : item.unitPrice().multiply(BigDecimal.valueOf(item.quantity()));
        return new CartItemView(
                item.id(),
                item.spuId(),
                item.externalRef(),
                item.title(),
                item.brand(),
                item.imageUrl(),
                item.quantity(),
                item.unitPrice(),
                lineAmount,
                item.stockSnapshot()
        );
    }

    private int effectiveQuantity(Integer quantity) {
        return quantity == null || quantity <= 0 ? 1 : quantity;
    }

    private BigDecimal displayPrice(CatalogProductView product) {
        return product.priceMin() != null ? product.priceMin() : product.priceMax();
    }

    private String firstImage(CatalogProductView product) {
        return product.imagePath();
    }
}
