package com.bytedance.ai.graph.cartmanage.subgraph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.cartmanage.CartMutationResult;
import com.bytedance.ai.graph.cartmanage.application.CartCommandService;
import com.bytedance.ai.graph.cartmanage.application.CartQueryService;
import com.bytedance.ai.graph.cartmanage.subgraph.CartAction;
import com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys;
import com.bytedance.ai.graph.cartmanage.subgraph.CartWorkflowStatus;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartActionParser;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartGraphStateSupport;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartItemLookup;
import com.bytedance.ai.graph.orchestration.GuideGraphStateKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public class CartExecuteActionNode {

    private static final Logger log = LoggerFactory.getLogger(CartExecuteActionNode.class);

    private final CartQueryService cartQueryService;
    private final CartCommandService cartCommandService;
    private final CartItemLookup cartItemLookup;

    public CartExecuteActionNode(
            CartQueryService cartQueryService,
            CartCommandService cartCommandService,
            CartItemLookup cartItemLookup
    ) {
        this.cartQueryService = cartQueryService;
        this.cartCommandService = cartCommandService;
        this.cartItemLookup = cartItemLookup;
    }

    public Map<String, Object> apply(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        String userId = CartGraphStateSupport.requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = CartGraphStateSupport.requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        CartAction action = CartActionParser.safeCartAction(
                state.value(CartGraphStateKeys.CART_ACTION, CartAction.UNKNOWN.name())
        );

        CartView cart = cartQueryService.getUserCart(userId, conversationId);
        log.info("Cart execute action start: userId={}, conversationId={}, action={}, cartItems={}",
                userId, conversationId, action, cart == null || cart.items() == null ? 0 : cart.items().size());

        switch (action) {
            case VIEW -> {
                updates.put(CartGraphStateKeys.CART_RESULT, cart);
                updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.VIEW_SUCCESS.name());
                updates.put(CartGraphStateKeys.NODE_MESSAGE, formatCartViewMessage(cart));
            }
            case CLEAR -> {
                var result = cartCommandService.clearCart(userId, conversationId);
                updates.put(CartGraphStateKeys.CART_RESULT, result);
                if (putMutationFailure(updates, result)) {
                    break;
                }
                updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.CLEAR_SUCCESS.name());
                updates.put(CartGraphStateKeys.NODE_MESSAGE, "购物车已清空。");
            }
            case ADD, CONFIRM -> {
                String productId = state.value(CartGraphStateKeys.PRODUCT_ID, "");
                String skuId = state.value(CartGraphStateKeys.SKU_ID, "");
                int quantity = state.value(CartGraphStateKeys.QUANTITY, 1);
                BigDecimal expectedPrice = state.value(CartGraphStateKeys.EXPECTED_PRICE, BigDecimal.class)
                        .orElse(null);
                var mutation = cartCommandService.addItem(userId, conversationId, productId, skuId, quantity, expectedPrice);
                updates.put(CartGraphStateKeys.CART_RESULT, mutation);
                if (putMutationFailure(updates, mutation)) {
                    break;
                }
                updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.ADD_SUCCESS.name());
                String productName = state.value(CartGraphStateKeys.PRODUCT_NAME, "该商品");
                updates.put(CartGraphStateKeys.NODE_MESSAGE, "已将「" + productName + "」加入购物车，数量 " + quantity + "。");
            }
            case REMOVE -> {
                Integer itemIndex = state.<Integer>value(CartGraphStateKeys.ITEM_INDEX).orElse(null);
                String productName = state.value(CartGraphStateKeys.PRODUCT_NAME, "");
                String productId = state.value(CartGraphStateKeys.PRODUCT_ID, "");
                String skuId = state.value(CartGraphStateKeys.SKU_ID, "");
                CartItemView target = cartItemLookup.find(cart, itemIndex, productName, productId, skuId);
                log.info("Cart execute remove target resolved: itemIndex={}, productName={}, productId={}, skuId={}, targetItemId={}",
                        itemIndex, productName, productId, skuId, target == null ? null : target.itemId());
                if (target != null) {
                    var mutation = cartCommandService.removeItem(userId, conversationId, String.valueOf(target.itemId()));
                    updates.put(CartGraphStateKeys.CART_RESULT, mutation);
                    if (putMutationFailure(updates, mutation)) {
                        break;
                    }
                    updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.REMOVE_SUCCESS.name());
                    updates.put(CartGraphStateKeys.NODE_MESSAGE, "已从购物车删除该商品。");
                } else {
                    updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.ITEM_NOT_FOUND.name());
                    updates.put(CartGraphStateKeys.NODE_MESSAGE, "购物车里没有找到该商品，请换个说法，或先查看购物车。");
                }
            }
            case UPDATE_QUANTITY -> {
                Integer itemIndex = state.<Integer>value(CartGraphStateKeys.ITEM_INDEX).orElse(null);
                Integer quantity = state.value(CartGraphStateKeys.QUANTITY, 1);
                String productName = state.value(CartGraphStateKeys.PRODUCT_NAME, "");
                String productId = state.value(CartGraphStateKeys.PRODUCT_ID, "");
                String skuId = state.value(CartGraphStateKeys.SKU_ID, "");
                CartItemView target = cartItemLookup.find(cart, itemIndex, productName, productId, skuId);
                log.info("Cart execute update target resolved: itemIndex={}, productName={}, productId={}, skuId={}, quantity={}, targetItemId={}",
                        itemIndex, productName, productId, skuId, quantity, target == null ? null : target.itemId());
                if (target != null) {
                    var mutation = cartCommandService.updateQuantity(userId, conversationId, String.valueOf(target.itemId()), quantity);
                    updates.put(CartGraphStateKeys.CART_RESULT, mutation);
                    if (putMutationFailure(updates, mutation)) {
                        break;
                    }
                    updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.UPDATE_SUCCESS.name());
                    updates.put(CartGraphStateKeys.NODE_MESSAGE, "已更新购物车商品数量。");
                } else {
                    updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.ITEM_NOT_FOUND.name());
                    updates.put(CartGraphStateKeys.NODE_MESSAGE, "购物车里没有找到该商品，请换个说法，或先查看购物车。");
                }
            }
            default -> {
                updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.FAILED.name());
                updates.put(CartGraphStateKeys.NODE_MESSAGE, "我没太理解你的购物车操作。");
            }
        }

        log.info("Executed cart action: {}, status: {}", action,
                updates.get(CartGraphStateKeys.WORKFLOW_STATUS));
        return updates;
    }

    private boolean putMutationFailure(Map<String, Object> updates, CartMutationResult mutation) {
        if (mutation == null || mutation.success()) {
            return false;
        }
        updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.FAILED.name());
        updates.put(CartGraphStateKeys.NEED_USER_INPUT, false);
        updates.put(CartGraphStateKeys.NODE_MESSAGE, mutationFailureMessage(mutation));
        return true;
    }

    private String mutationFailureMessage(CartMutationResult mutation) {
        String message = mutation.errorMessage();
        if (message == null || message.isBlank()) {
            return "购物车操作失败，请稍后重试。";
        }
        if (message.contains("价格已变化") || message.contains("价格发生变化")) {
            return "该商品价格发生变化，暂时不能直接加入购物车，请重新确认商品后再添加。";
        }
        return message;
    }

    private String formatCartViewMessage(CartView cart) {
        if (cart == null || cart.items() == null || cart.items().isEmpty()) {
            return "你的购物车目前是空的。";
        }
        StringBuilder sb = new StringBuilder("你的购物车商品列表：\n");
        for (int i = 0; i < cart.items().size(); i++) {
            CartItemView item = cart.items().get(i);
            sb.append(i + 1).append(". ").append(item.title()).append(" x").append(item.quantity()).append("\n");
        }
        return sb.toString().trim();
    }
}
