package com.bytedance.ai.graph.ordermanage;

import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartQueryFacade;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.conversation.context.ConversationContextItemStatus;
import com.bytedance.ai.graph.conversation.context.ConversationContextManager;
import com.bytedance.ai.graph.conversation.context.ConversationRuntimeContext;
import com.bytedance.ai.graph.orchestration.GuideGraphContextSupport;
import com.bytedance.ai.graph.orchestration.GuideGraphStateKeys;
import com.bytedance.ai.graph.ordermanage.application.OrderAddressResolver;
import com.bytedance.ai.graph.ordermanage.application.OrderCartSnapshotService;
import com.bytedance.ai.graph.ordermanage.application.OrderCommandService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 订单管理子图工厂，装配下单/确认流程的节点流转。
 */
@Component
public class OrderManageSubgraphFactory {

    private static final String EMPTY_CART_MESSAGE = "你的购物车目前是空的，请先将商品添加到购物车后再结算。";
    private static final String PRODUCT_NOT_IN_CART_MESSAGE = "该商品还没有添加到购物车，请先添加到购物车后再结算。";

    private final CartQueryFacade cartQueryFacade;
    private final CatalogQueryFacade catalogQueryFacade;
    private final ConversationContextManager conversationContextManager;
    private final OrderAddressResolver addressResolver;
    private final OrderCartSnapshotService snapshotService;
    private final OrderCommandService orderCommandService;

    public OrderManageSubgraphFactory(
            CartQueryFacade cartQueryFacade,
            CatalogQueryFacade catalogQueryFacade,
            ConversationContextManager conversationContextManager,
            OrderAddressResolver addressResolver,
            OrderCartSnapshotService snapshotService,
            OrderCommandService orderCommandService
    ) {
        this.cartQueryFacade = cartQueryFacade;
        this.catalogQueryFacade = catalogQueryFacade;
        this.conversationContextManager = conversationContextManager;
        this.addressResolver = addressResolver;
        this.snapshotService = snapshotService;
        this.orderCommandService = orderCommandService;
    }

    public StateGraph build() {
        try {
            StateGraph subgraph = new StateGraph("order_manage_subgraph", keyStrategyFactory());
            subgraph.addNode("order_load_context", AsyncNodeAction.node_async(this::orderLoadContext));
            subgraph.addNode("order_resolve_action", AsyncNodeAction.node_async(this::orderResolveAction));
            subgraph.addNode("order_load_cart", AsyncNodeAction.node_async(this::orderLoadCart));
            subgraph.addNode("order_check_stock", AsyncNodeAction.node_async(this::orderCheckStock));
            subgraph.addNode("order_resolve_address", AsyncNodeAction.node_async(this::orderResolveAddress));
            subgraph.addNode("order_build_summary", AsyncNodeAction.node_async(this::orderBuildSummary));
            subgraph.addNode("order_execute_create", AsyncNodeAction.node_async(this::orderExecuteCreate));
            subgraph.addNode("order_cancel_order", AsyncNodeAction.node_async(this::orderCancelOrder));
            subgraph.addNode("order_final_response", AsyncNodeAction.node_async(this::orderFinalResponse));

            subgraph.addEdge(StateGraph.START, "order_load_context");
            subgraph.addEdge("order_load_context", "order_resolve_action");
            subgraph.addConditionalEdges("order_resolve_action",
                    AsyncEdgeAction.edge_async(this::routeAfterResolveAction),
                    Map.of(
                            OrderManageAction.CHECKOUT_REQUEST.name(), "order_load_cart",
                            OrderManageAction.PROVIDE_ADDRESS.name(), "order_resolve_address",
                            OrderManageAction.CONFIRM_ORDER.name(), "order_execute_create",
                            OrderManageAction.CANCEL_ORDER.name(), "order_cancel_order",
                            OrderManageAction.UNKNOWN.name(), "order_final_response"
                    ));
            subgraph.addConditionalEdges("order_load_cart",
                    AsyncEdgeAction.edge_async(this::routeAfterLoadCart),
                    Map.of(
                            "EMPTY_CART", "order_final_response",
                            "CART_GUARD_FAILED", "order_final_response",
                            "HAS_CART", "order_check_stock"
                    ));
            subgraph.addConditionalEdges("order_check_stock",
                    AsyncEdgeAction.edge_async(this::routeAfterCheckStock),
                    Map.of("STOCK_OK", "order_resolve_address", "STOCK_NOT_ENOUGH", "order_final_response"));
            subgraph.addConditionalEdges("order_resolve_address",
                    AsyncEdgeAction.edge_async(this::routeAfterResolveAddress),
                    Map.of("ADDRESS_READY", "order_build_summary", "ADDRESS_MISSING", "order_final_response"));
            subgraph.addEdge("order_build_summary", "order_final_response");
            subgraph.addEdge("order_execute_create", "order_final_response");
            subgraph.addEdge("order_cancel_order", "order_final_response");
            subgraph.addEdge("order_final_response", StateGraph.END);
            return subgraph;
        } catch (com.alibaba.cloud.ai.graph.exception.GraphStateException exception) {
            throw new IllegalStateException("order_manage_subgraph compile failed", exception);
        }
    }

    private KeyStrategyFactory keyStrategyFactory() {
        return new KeyStrategyFactoryBuilder().defaultStrategy(new ReplaceStrategy()).build();
    }

    private Map<String, Object> orderLoadContext(OverAllState state) {
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        String message = state.value(GuideGraphStateKeys.MESSAGE, "");
        Map<String, Object> updates = new LinkedHashMap<>();
        clearTransientState(updates);

        ConversationRuntimeContext context = GuideGraphContextSupport.loadContext(conversationContextManager, state);
        ConversationRuntimeContext.OrderContext order = context == null ? null : context.order();
        if (order != null) {
            if (order.expiresAt() != null && order.expiresAt().isBefore(LocalDateTime.now())) {
                conversationContextManager.markContextItemStatus(
                        order.contextItemId(),
                        ConversationContextItemStatus.EXPIRED
                );
                updates.put(OrderManageStateKeys.ORDER_STATUS, OrderManageStatus.EXPIRED.name());
                updates.put(OrderManageStateKeys.NODE_MESSAGE, "本次下单确认已过期，请重新发送‘结算购物车’。");
                updates.put(OrderManageStateKeys.NEED_USER_INPUT, false);
            } else {
                writeOrderContext(updates, order);
            }
        }
        if (context != null && context.pendingClarification() != null && looksLikeCheckout(message)) {
            conversationContextManager.markContextItemStatus(
                    context.pendingClarification().contextItemId(),
                    ConversationContextItemStatus.CANCELLED
            );
        }
        return updates;
    }

    private Map<String, Object> orderResolveAction(OverAllState state) {
        String message = state.value(GuideGraphStateKeys.MESSAGE, "");
        boolean hasPending = state.value(OrderManageStateKeys.ORDER_CONTEXT_ITEM_ID).isPresent();
        OrderManageStatus status = parseStatus(state.value(OrderManageStateKeys.ORDER_STATUS, ""));
        boolean addressInput = hasAddressInput(state);
        OrderManageAction action;
        if (hasPending && looksLikeCancel(message)) {
            action = OrderManageAction.CANCEL_ORDER;
        } else if (hasPending && status == OrderManageStatus.WAITING_ADDRESS && addressInput) {
            action = OrderManageAction.PROVIDE_ADDRESS;
        } else if (hasPending && status == OrderManageStatus.WAITING_CONFIRMATION && looksLikeConfirmOrder(message)) {
            action = OrderManageAction.CONFIRM_ORDER;
        } else if (!hasPending && looksLikeConfirmOrder(message)) {
            action = OrderManageAction.CONFIRM_ORDER;
        } else if (!hasPending && (looksLikeCheckout(message) || isOrderIntent(state) || addressInput)) {
            action = OrderManageAction.CHECKOUT_REQUEST;
        } else if (hasPending && status == OrderManageStatus.WAITING_ADDRESS && looksLikeConfirmOrder(message)) {
            action = OrderManageAction.CONFIRM_ORDER;
        } else {
            action = OrderManageAction.UNKNOWN;
        }
        return Map.of(OrderManageStateKeys.ORDER_ACTION, action.name());
    }

    private String routeAfterResolveAction(OverAllState state) {
        return state.value(OrderManageStateKeys.ORDER_ACTION, OrderManageAction.UNKNOWN.name());
    }

    private Map<String, Object> orderLoadCart(OverAllState state) {
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        Map<String, Object> updates = new LinkedHashMap<>();
        CartView cart = cartQueryFacade.getActiveCart(userId, conversationId);
        if (cart == null || cart.items() == null || cart.items().isEmpty()) {
            updates.put(OrderManageStateKeys.ORDER_STATUS, OrderManageStatus.FAILED.name());
            updates.put(OrderManageStateKeys.ERROR_REASON, "cart empty");
            updates.put(OrderManageStateKeys.NODE_MESSAGE, EMPTY_CART_MESSAGE);
            updates.put(OrderManageStateKeys.NEED_USER_INPUT, false);
            updates.put("orderLoadCartRoute", "EMPTY_CART");
            return updates;
        }
        if (!requestedProductAlreadyInCart(state, cart)) {
            updates.put(OrderManageStateKeys.ORDER_STATUS, OrderManageStatus.FAILED.name());
            updates.put(OrderManageStateKeys.ERROR_REASON, "requested product not in cart");
            updates.put(OrderManageStateKeys.NODE_MESSAGE, PRODUCT_NOT_IN_CART_MESSAGE);
            updates.put(OrderManageStateKeys.NEED_USER_INPUT, false);
            updates.put("orderLoadCartRoute", "CART_GUARD_FAILED");
            return updates;
        }
        Map<String, Object> cartSnapshot = snapshotService.snapshot(cart);
        String cartSnapshotHash = snapshotService.hash(cartSnapshot);
        BigDecimal amount = snapshotService.amount(cart);
        ConversationRuntimeContext.OrderContext orderContext = conversationContextManager.updateOrderContext(
                userId,
                conversationId,
                state.value(GuideGraphStateKeys.RUN_ID, ""),
                "order_manage_workflow",
                new ConversationRuntimeContext.OrderContext(
                        null,
                        cartSnapshot,
                        cartSnapshotHash,
                        Map.of(),
                        amount,
                        OrderManageStatus.WAITING_ADDRESS.name(),
                        null,
                        null,
                        LocalDateTime.now().plusMinutes(30),
                        Map.of()
                ),
                LocalDateTime.now().plusMinutes(30)
        );
        writeOrderContext(updates, orderContext);
        updates.put("orderLoadCartRoute", "HAS_CART");
        return updates;
    }

    private String routeAfterLoadCart(OverAllState state) {
        return state.value("orderLoadCartRoute", "EMPTY_CART");
    }

    private Map<String, Object> orderCheckStock(OverAllState state) {
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        Map<String, Object> updates = new LinkedHashMap<>();
        String stockError = validateStock(cartQueryFacade.getActiveCart(userId, conversationId));
        if (stockError != null) {
            pendingId(state).ifPresent(id -> conversationContextManager.markContextItemStatus(
                    id,
                    ConversationContextItemStatus.FAILED
            ));
            updates.put(OrderManageStateKeys.ORDER_STATUS, OrderManageStatus.FAILED.name());
            updates.put(OrderManageStateKeys.ERROR_REASON, stockError);
            updates.put(OrderManageStateKeys.NODE_MESSAGE, "部分商品库存不足，暂时无法下单：" + stockError);
            updates.put(OrderManageStateKeys.NEED_USER_INPUT, false);
            updates.put("orderCheckStockRoute", "STOCK_NOT_ENOUGH");
            return updates;
        }
        updates.put("orderCheckStockRoute", "STOCK_OK");
        return updates;
    }

    private String routeAfterCheckStock(OverAllState state) {
        return state.value("orderCheckStockRoute", "STOCK_NOT_ENOUGH");
    }

    private Map<String, Object> orderResolveAddress(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        Optional<Long> pendingId = pendingId(state);
        if (pendingId.isEmpty()) {
            updates.put(OrderManageStateKeys.ORDER_STATUS, OrderManageStatus.FAILED.name());
            updates.put(OrderManageStateKeys.NODE_MESSAGE, "当前没有待确认的订单，请先发送‘结算购物车’。");
            updates.put("orderResolveAddressRoute", "ADDRESS_MISSING");
            return updates;
        }
        AddressSnapshot existing = AddressSnapshot.fromMap(state.value(OrderManageStateKeys.ADDRESS_SNAPSHOT, Map.<String, Object>of()));
        if (StringUtils.hasText(existing.receiverName())
                && StringUtils.hasText(existing.phone())
                && StringUtils.hasText(existing.addressText())) {
            updates.put("orderResolveAddressRoute", "ADDRESS_READY");
            return updates;
        }
        if (!hasAddressInput(state)) {
            updates.put(OrderManageStateKeys.ORDER_STATUS, OrderManageStatus.WAITING_ADDRESS.name());
            updates.put(OrderManageStateKeys.NODE_MESSAGE,
                    addressResolver.missingFieldsMessage(List.of("receiverName", "phone", "addressText")));
            updates.put(OrderManageStateKeys.NEED_USER_INPUT, true);
            updates.put("orderResolveAddressRoute", "ADDRESS_MISSING");
            return updates;
        }
        AddressParseResult parsed = parseAddress(state);
        if (!parsed.complete()) {
            updates.put(OrderManageStateKeys.ORDER_STATUS, OrderManageStatus.WAITING_ADDRESS.name());
            updates.put(OrderManageStateKeys.NODE_MESSAGE, addressResolver.missingFieldsMessage(parsed.missingFields()));
            updates.put(OrderManageStateKeys.NEED_USER_INPUT, true);
            updates.put("orderResolveAddressRoute", "ADDRESS_MISSING");
            return updates;
        }
        Map<String, Object> address = parsed.snapshot().toMap();
        ConversationRuntimeContext.OrderContext updated = conversationContextManager.updateOrderContext(
                requiredString(state, GuideGraphStateKeys.USER_ID),
                requiredString(state, GuideGraphStateKeys.CONVERSATION_ID),
                state.value(GuideGraphStateKeys.RUN_ID, ""),
                "order_manage_workflow",
                orderContextFromState(state, OrderManageStatus.WAITING_ADDRESS.name(), address, null, null),
                LocalDateTime.now().plusMinutes(30)
        );
        updates.put(OrderManageStateKeys.ORDER_CONTEXT_ITEM_ID, updated.contextItemId());
        updates.put(OrderManageStateKeys.ADDRESS_SNAPSHOT, address);
        updates.put("orderResolveAddressRoute", "ADDRESS_READY");
        return updates;
    }

    private String routeAfterResolveAddress(OverAllState state) {
        return state.value("orderResolveAddressRoute", "ADDRESS_MISSING");
    }

    private Map<String, Object> orderBuildSummary(OverAllState state) {
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        CartView cart = cartQueryFacade.getActiveCart(userId, conversationId);
        Map<String, Object> cartSnapshot = snapshotService.snapshot(cart);
        String cartSnapshotHash = snapshotService.hash(cartSnapshot);
        BigDecimal amount = snapshotService.amount(cart);
        Map<String, Object> address = state.value(OrderManageStateKeys.ADDRESS_SNAPSHOT, Map.<String, Object>of());
        ConversationRuntimeContext.OrderContext updated = conversationContextManager.updateOrderContext(
                userId,
                conversationId,
                state.value(GuideGraphStateKeys.RUN_ID, ""),
                "order_manage_workflow",
                new ConversationRuntimeContext.OrderContext(
                        null,
                        cartSnapshot,
                        cartSnapshotHash,
                        address,
                        amount,
                        OrderManageStatus.WAITING_CONFIRMATION.name(),
                        null,
                        null,
                        LocalDateTime.now().plusMinutes(30),
                        Map.of()
                ),
                LocalDateTime.now().plusMinutes(30)
        );
        return Map.of(
                OrderManageStateKeys.ORDER_CONTEXT_ITEM_ID, updated.contextItemId(),
                OrderManageStateKeys.ORDER_STATUS, OrderManageStatus.WAITING_CONFIRMATION.name(),
                OrderManageStateKeys.CART_SNAPSHOT, cartSnapshot,
                OrderManageStateKeys.CART_SNAPSHOT_HASH, cartSnapshotHash,
                OrderManageStateKeys.AMOUNT_SNAPSHOT, amount,
                OrderManageStateKeys.NEED_USER_INPUT, true,
                OrderManageStateKeys.NODE_MESSAGE, summaryMessage(cart, AddressSnapshot.fromMap(address), amount)
        );
    }

    private Map<String, Object> orderExecuteCreate(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        Optional<Long> pendingId = pendingId(state);
        OrderManageStatus status = parseStatus(state.value(OrderManageStateKeys.ORDER_STATUS, ""));
        if (pendingId.isEmpty()) {
            updates.put(OrderManageStateKeys.ORDER_STATUS, OrderManageStatus.FAILED.name());
            updates.put(OrderManageStateKeys.NODE_MESSAGE, "当前没有待确认的订单，请先发送‘结算购物车’。");
            return updates;
        }
        if (status == OrderManageStatus.WAITING_ADDRESS) {
            updates.put(OrderManageStateKeys.ORDER_STATUS, OrderManageStatus.WAITING_ADDRESS.name());
            updates.put(OrderManageStateKeys.NODE_MESSAGE, "请先提供收货地址后再确认下单。");
            updates.put(OrderManageStateKeys.NEED_USER_INPUT, true);
            return updates;
        }
        if (status != OrderManageStatus.WAITING_CONFIRMATION) {
            updates.put(OrderManageStateKeys.ORDER_STATUS, OrderManageStatus.FAILED.name());
            updates.put(OrderManageStateKeys.NODE_MESSAGE, "当前没有等待确认的订单，不能直接确认下单。");
            return updates;
        }
        OrderCreateResult result = orderCommandService.createMockOrderFromContext(
                requiredString(state, GuideGraphStateKeys.USER_ID),
                requiredString(state, GuideGraphStateKeys.CONVERSATION_ID),
                orderContextFromState(state, OrderManageStatus.WAITING_CONFIRMATION.name(), null, null, null)
        );
        updates.put(OrderManageStateKeys.ORDER_STATUS, result.status().name());
        updates.put(OrderManageStateKeys.NODE_MESSAGE, result.message());
        updates.put(OrderManageStateKeys.NEED_USER_INPUT, false);
        if (result.orderNo() != null) {
            updates.put(OrderManageStateKeys.ORDER_NO, result.orderNo());
        }
        return updates;
    }

    private Map<String, Object> orderCancelOrder(OverAllState state) {
        Optional<Long> pendingId = pendingId(state);
        if (pendingId.isEmpty()) {
            return Map.of(OrderManageStateKeys.NODE_MESSAGE, "当前没有待处理订单，无需取消。");
        }
        conversationContextManager.markContextItemStatus(
                pendingId.get(),
                ConversationContextItemStatus.CANCELLED
        );
        return Map.of(
                OrderManageStateKeys.ORDER_STATUS, OrderManageStatus.CANCELLED.name(),
                OrderManageStateKeys.NODE_MESSAGE, "已取消这次待确认订单，没有扣减库存，也没有创建订单。",
                OrderManageStateKeys.NEED_USER_INPUT, false
        );
    }

    private Map<String, Object> orderFinalResponse(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        if (state.value(OrderManageStateKeys.NODE_MESSAGE).isEmpty()) {
            if (OrderManageAction.UNKNOWN.name().equals(state.value(OrderManageStateKeys.ORDER_ACTION, ""))
                    && state.value(OrderManageStateKeys.ORDER_CONTEXT_ITEM_ID).isEmpty()) {
                updates.put(OrderManageStateKeys.NODE_MESSAGE,
                        "当前没有待确认的订单，请先发送“结算购物车”，我会基于当前购物车生成待确认订单。");
                updates.put(OrderManageStateKeys.NEED_USER_INPUT, false);
            } else {
                updates.put(OrderManageStateKeys.NODE_MESSAGE,
                        "请提供收货人姓名、联系电话和详细收货地址，例如：Zhang，0412345678，UNSW High Street, Kensington NSW 2052。");
                updates.put(OrderManageStateKeys.NEED_USER_INPUT, true);
            }
        }
        // 不管走的是 NODE_MESSAGE-already-set 分支还是兜底分支，都把结构化结果统一发给主图。
        String nodeMessage = updates.containsKey(OrderManageStateKeys.NODE_MESSAGE)
                ? (String) updates.get(OrderManageStateKeys.NODE_MESSAGE)
                : state.value(OrderManageStateKeys.NODE_MESSAGE, "");
        Boolean needUserInput = updates.containsKey(OrderManageStateKeys.NEED_USER_INPUT)
                ? (Boolean) updates.get(OrderManageStateKeys.NEED_USER_INPUT)
                : state.value(OrderManageStateKeys.NEED_USER_INPUT, false);
        updates.put(GuideGraphStateKeys.WORKFLOW_RESULT, new OrderManageWorkflowResult(
                state.value(OrderManageStateKeys.ORDER_ACTION, ""),
                state.value(OrderManageStateKeys.ORDER_STATUS, ""),
                state.value(OrderManageStateKeys.ORDER_NO, (String) null),
                state.value(OrderManageStateKeys.AMOUNT_SNAPSHOT, BigDecimal.class).orElse(null),
                state.value(OrderManageStateKeys.ADDRESS_SNAPSHOT, Map.class)
                        .map(m -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> cast = (Map<String, Object>) m;
                            return cast;
                        })
                        .orElse(Map.of()),
                state.value(OrderManageStateKeys.ERROR_REASON, (String) null),
                Boolean.TRUE.equals(needUserInput),
                nodeMessage
        ));
        return updates;
    }

    private void clearTransientState(Map<String, Object> updates) {
        for (String key : List.of(
                OrderManageStateKeys.ORDER_ACTION,
                OrderManageStateKeys.ORDER_CONTEXT_ITEM_ID,
                OrderManageStateKeys.ORDER_STATUS,
                OrderManageStateKeys.CART_SNAPSHOT,
                OrderManageStateKeys.CART_SNAPSHOT_HASH,
                OrderManageStateKeys.ADDRESS_SNAPSHOT,
                OrderManageStateKeys.AMOUNT_SNAPSHOT,
                OrderManageStateKeys.ORDER_NO,
                OrderManageStateKeys.NODE_MESSAGE,
                OrderManageStateKeys.NEED_USER_INPUT,
                OrderManageStateKeys.ERROR_REASON,
                "orderLoadCartRoute",
                "orderCheckStockRoute",
                "orderResolveAddressRoute"
        )) {
            updates.put(key, OverAllState.MARK_FOR_REMOVAL);
        }
    }

    private void writeOrderContext(Map<String, Object> updates, ConversationRuntimeContext.OrderContext order) {
        updates.put(OrderManageStateKeys.ORDER_CONTEXT_ITEM_ID, order.contextItemId());
        updates.put(OrderManageStateKeys.ORDER_STATUS, order.orderStatus());
        updates.put(OrderManageStateKeys.CART_SNAPSHOT, order.cartSnapshot());
        updates.put(OrderManageStateKeys.CART_SNAPSHOT_HASH, order.cartSnapshotHash());
        updates.put(OrderManageStateKeys.ADDRESS_SNAPSHOT, order.addressSnapshot());
        updates.put(OrderManageStateKeys.AMOUNT_SNAPSHOT, order.amountSnapshot());
    }

    private boolean looksLikeCheckout(String message) {
        String text = normalize(message);
        return text.contains("结算购物车")
                || text.contains("我要下单")
                || text.contains("帮我下单")
                || text.contains("提交订单")
                || text.contains("购买购物车")
                || text.contains("买这些")
                || text.equals("checkout");
    }

    private boolean isOrderIntent(OverAllState state) {
        String intent = state.value(GuideGraphStateKeys.INTENT)
                .map(Object::toString)
                .orElse("");
        String mainIntent = state.value(GuideGraphStateKeys.MAIN_INTENT)
                .map(Object::toString)
                .orElse("");
        return "CREATE_ORDER".equals(intent)
                || "CREATE_ORDER".equals(mainIntent)
                || "CONFIRM_ORDER".equals(intent)
                || "CONFIRM_ORDER".equals(mainIntent);
    }

    private boolean hasAddressInput(OverAllState state) {
        return hasAddressSlots(state) || addressResolver.looksLikeAddress(state.value(GuideGraphStateKeys.MESSAGE, ""));
    }

    private boolean hasAddressSlots(OverAllState state) {
        Map<String, Object> slots = intentSlots(state);
        return firstSlot(slots,
                "receiverName", "receiver_name", "recipientName", "recipient_name", "name") != null
                || firstSlot(slots,
                "phone", "mobile", "contactNumber", "contact_number", "telephone") != null
                || firstSlot(slots,
                "address", "addressText", "address_text", "shippingAddress", "shipping_address", "detail") != null;
    }

    private boolean requestedProductAlreadyInCart(OverAllState state, CartView cart) {
        RequestedProductReference requested = requestedProductReference(state);
        if (requested == null) {
            return true;
        }
        if (cart == null || cart.items() == null) {
            return false;
        }
        for (CartItemView item : cart.items()) {
            if (matchesRequestedProduct(requested, item)) {
                return true;
            }
        }
        return false;
    }

    private RequestedProductReference requestedProductReference(OverAllState state) {
        Map<String, Object> slots = intentSlots(state);
        String productId = firstSlot(slots, "product_id", "productId", "spu_id", "spuId");
        String skuId = firstSlot(slots, "sku_id", "skuId");
        String productRef = firstSlot(slots, "product_ref", "productRef", "external_ref", "externalRef");
        String productName = firstSlot(slots, "product_name", "productName", "title");
        if (!StringUtils.hasText(productId)
                && !StringUtils.hasText(skuId)
                && !StringUtils.hasText(productRef)
                && !StringUtils.hasText(productName)) {
            return null;
        }
        return new RequestedProductReference(productId, skuId, productRef, productName);
    }

    private boolean matchesRequestedProduct(RequestedProductReference requested, CartItemView item) {
        if (item == null) {
            return false;
        }
        if (StringUtils.hasText(requested.productId())) {
            return matchesProductId(requested.productId(), item);
        }
        if (StringUtils.hasText(requested.skuId())) {
            return matchesSkuId(requested.skuId(), item);
        }
        if (StringUtils.hasText(requested.productRef())) {
            return textMatches(requested.productRef(), item.externalRef())
                    || textMatches(requested.productRef(), item.title());
        }
        return textMatches(requested.productName(), item.title())
                || textMatches(requested.productName(), item.externalRef());
    }

    private boolean matchesProductId(String productId, CartItemView item) {
        Optional<Long> numericProductId = parseLong(productId);
        if (numericProductId.isPresent() && numericProductId.get().equals(item.spuId())) {
            return true;
        }
        String token = normalizeToken(productId);
        String externalRef = normalizeToken(item.externalRef());
        return StringUtils.hasText(token)
                && (token.equals(externalRef) || ("spu-" + token).equals(externalRef));
    }

    private boolean matchesSkuId(String skuId, CartItemView item) {
        Optional<Long> numericSkuId = parseLong(skuId);
        if (numericSkuId.isPresent() && numericSkuId.get().equals(item.skuId())) {
            return true;
        }
        return textContains(item.externalRef(), skuId);
    }

    private Optional<Long> parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private boolean textContains(String value, String token) {
        String normalizedValue = normalizeToken(value);
        String normalizedToken = normalizeToken(token);
        return StringUtils.hasText(normalizedValue)
                && StringUtils.hasText(normalizedToken)
                && normalizedValue.contains(normalizedToken);
    }

    private boolean textMatches(String requested, String value) {
        String normalizedRequested = normalizeToken(requested);
        String normalizedValue = normalizeToken(value);
        if (!StringUtils.hasText(normalizedRequested) || !StringUtils.hasText(normalizedValue)) {
            return false;
        }
        if (normalizedRequested.length() < 2) {
            return normalizedRequested.equals(normalizedValue);
        }
        return normalizedValue.contains(normalizedRequested) || normalizedRequested.contains(normalizedValue);
    }

    private String normalizeToken(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", "");
    }

    private AddressParseResult parseAddress(OverAllState state) {
        Map<String, Object> slots = intentSlots(state);
        String slotReceiver = firstSlot(slots,
                "receiverName", "receiver_name", "recipientName", "recipient_name", "name");
        String slotPhone = firstSlot(slots,
                "phone", "mobile", "contactNumber", "contact_number", "telephone");
        String slotAddress = firstSlot(slots,
                "address", "addressText", "address_text", "shippingAddress", "shipping_address", "detail");

        String message = state.value(GuideGraphStateKeys.MESSAGE, "");
        String combined = String.join(" ",
                List.of(
                        nullToEmpty(slotReceiver),
                        nullToEmpty(slotPhone),
                        nullToEmpty(slotAddress),
                        nullToEmpty(message)
                ));
        AddressParseResult parsed = addressResolver.parse(combined);
        AddressSnapshot parsedSnapshot = parsed.snapshot();
        String receiver = firstNonBlank(slotReceiver, parsedSnapshot.receiverName());
        String phone = firstNonBlank(slotPhone, parsedSnapshot.phone());
        String address = firstNonBlank(slotAddress, parsedSnapshot.addressText());
        String postcode = parsedSnapshot.postcode();
        String city = parsedSnapshot.city();
        String stateName = parsedSnapshot.state();
        AddressSnapshot snapshot = new AddressSnapshot(receiver, phone, address, postcode, city, stateName);

        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(receiver)) {
            missing.add("receiverName");
        }
        if (!StringUtils.hasText(phone)) {
            missing.add("phone");
        }
        if (!StringUtils.hasText(address)) {
            missing.add("addressText");
        }
        return new AddressParseResult(missing.isEmpty(), List.copyOf(missing), snapshot);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> intentSlots(OverAllState state) {
        Object value = state.value(GuideGraphStateKeys.INTENT_SLOTS).orElse(null);
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return normalized;
        }
        return Map.of();
    }

    private String firstSlot(Map<String, Object> slots, String... keys) {
        for (String key : keys) {
            Object value = slots.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record RequestedProductReference(
            String productId,
            String skuId,
            String productRef,
            String productName
    ) {
    }

    private boolean looksLikeConfirmOrder(String message) {
        String text = normalize(message);
        return text.equals("确认")
                || text.equals("可以")
                || text.equals("没问题")
                || text.equals("就这样")
                || text.contains("确认下单")
                || text.contains("确认订单")
                || text.contains("提交订单");
    }

    private boolean looksLikeCancel(String message) {
        String text = normalize(message);
        return text.contains("取消") || text.contains("先不买") || text.contains("不要了");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String validateStock(CartView cart) {
        if (cart == null || cart.items() == null || cart.items().isEmpty()) {
            return "购物车为空";
        }
        for (CartItemView item : snapshotService.sortedItems(cart)) {
            CatalogProductView product = catalogQueryFacade.getProduct(item.spuId());
            int required = item.quantity() == null ? 0 : item.quantity();
            if (!"ACTIVE".equals(product.status()) || product.totalStock() == null || product.totalStock() < required) {
                return "「" + item.title() + "」库存不足或已下架";
            }
            String skuError = validateSkuStock(item, product, required);
            if (skuError != null) {
                return skuError;
            }
        }
        return null;
    }

    private String validateSkuStock(CartItemView item, CatalogProductView product, int required) {
        if (item.skuId() == null) {
            return null;
        }
        CatalogSkuView sku = product.skus() == null ? null : product.skus().stream()
                .filter(candidate -> candidate != null && item.skuId().equals(candidate.id()))
                .findFirst()
                .orElse(null);
        if (sku == null || !"ACTIVE".equals(sku.status()) || sku.stock() == null || sku.stock() < required) {
            return "「" + item.title() + "」SKU 库存不足或已下架";
        }
        return null;
    }

    private String summaryMessage(CartView cart, AddressSnapshot address, BigDecimal amount) {
        StringBuilder builder = new StringBuilder("请确认订单信息：\n商品：\n");
        int index = 1;
        for (CartItemView item : snapshotService.sortedItems(cart)) {
            builder.append(index++)
                    .append(". ")
                    .append(item.title())
                    .append(" x")
                    .append(item.quantity())
                    .append("，¥")
                    .append(item.unitPrice())
                    .append('\n');
        }
        builder.append("收货地址：\n")
                .append(address.receiverName()).append("，").append(address.phone()).append('\n')
                .append(address.addressText()).append('\n')
                .append("订单金额：¥").append(amount).append('\n')
                .append("确认下单请回复“确认下单”，取消请回复“取消”。");
        return builder.toString();
    }

    private Optional<Long> pendingId(OverAllState state) {
        return state.value(OrderManageStateKeys.ORDER_CONTEXT_ITEM_ID).map(value -> {
            if (value instanceof Number number) {
                return number.longValue();
            }
            return Long.parseLong(value.toString());
        });
    }

    private ConversationRuntimeContext.OrderContext orderContextFromState(
            OverAllState state,
            String status,
            Map<String, Object> addressOverride,
            String failReason,
            String orderNo
    ) {
        ConversationRuntimeContext context = GuideGraphContextSupport.loadContext(conversationContextManager, state);
        ConversationRuntimeContext.OrderContext existing = context == null ? null : context.order();
        Long id = pendingId(state).orElse(existing == null ? null : existing.contextItemId());
        Map<String, Object> cartSnapshot = state.value(OrderManageStateKeys.CART_SNAPSHOT, Map.<String, Object>of());
        String cartSnapshotHash = state.value(OrderManageStateKeys.CART_SNAPSHOT_HASH, "");
        Map<String, Object> address = addressOverride == null
                ? state.value(OrderManageStateKeys.ADDRESS_SNAPSHOT, Map.<String, Object>of())
                : addressOverride;
        BigDecimal amount = state.value(OrderManageStateKeys.AMOUNT_SNAPSHOT, BigDecimal.class).orElse(null);
        if (existing != null) {
            if (cartSnapshot.isEmpty()) {
                cartSnapshot = existing.cartSnapshot();
            }
            if (!StringUtils.hasText(cartSnapshotHash)) {
                cartSnapshotHash = existing.cartSnapshotHash();
            }
            if (address.isEmpty()) {
                address = existing.addressSnapshot();
            }
            if (amount == null) {
                amount = existing.amountSnapshot();
            }
        }
        return new ConversationRuntimeContext.OrderContext(
                id,
                cartSnapshot,
                cartSnapshotHash,
                address,
                amount,
                status,
                failReason,
                orderNo,
                existing == null ? LocalDateTime.now().plusMinutes(30) : existing.expiresAt(),
                existing == null ? Map.of() : existing.payload()
        );
    }

    private OrderManageStatus parseStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return OrderManageStatus.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String requiredString(OverAllState state, String key) {
        return state.value(key)
                .map(Object::toString)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Missing graph state: " + key));
    }
}
