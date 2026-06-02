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
import com.bytedance.ai.graph.conversation.context.ConversationContextItemStatus;
import com.bytedance.ai.graph.conversation.context.ConversationContextManager;
import com.bytedance.ai.graph.conversation.context.ConversationRuntimeContext;
import com.bytedance.ai.graph.orchestration.GuideGraphStateKeys;
import com.bytedance.ai.graph.ordermanage.application.OrderAddressResolver;
import com.bytedance.ai.graph.ordermanage.application.OrderCartSnapshotService;
import com.bytedance.ai.graph.ordermanage.application.OrderCommandService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 订单管理子图工厂，装配下单/确认流程的节点流转。
 */
@Component
public class OrderManageSubgraphFactory {

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
                    Map.of("EMPTY_CART", "order_final_response", "HAS_CART", "order_check_stock"));
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

        ConversationRuntimeContext context = state.value(
                GuideGraphStateKeys.CONVERSATION_CONTEXT,
                ConversationRuntimeContext.class
        ).orElse(null);
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
        OrderManageAction action;
        if (hasPending && looksLikeCancel(message)) {
            action = OrderManageAction.CANCEL_ORDER;
        } else if (hasPending && status == OrderManageStatus.WAITING_ADDRESS && addressResolver.looksLikeAddress(message)) {
            action = OrderManageAction.PROVIDE_ADDRESS;
        } else if (hasPending && status == OrderManageStatus.WAITING_CONFIRMATION && looksLikeConfirmOrder(message)) {
            action = OrderManageAction.CONFIRM_ORDER;
        } else if (!hasPending && looksLikeConfirmOrder(message)) {
            action = OrderManageAction.CONFIRM_ORDER;
        } else if (!hasPending && looksLikeCheckout(message)) {
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
            updates.put(OrderManageStateKeys.NODE_MESSAGE, "你的购物车目前是空的，无法下单。");
            updates.put(OrderManageStateKeys.NEED_USER_INPUT, false);
            updates.put("orderLoadCartRoute", "EMPTY_CART");
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
        AddressParseResult parsed = addressResolver.parse(state.value(GuideGraphStateKeys.MESSAGE, ""));
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
        if (state.value(OrderManageStateKeys.NODE_MESSAGE).isPresent()) {
            return Map.of();
        }
        return Map.of(
                OrderManageStateKeys.NODE_MESSAGE,
                "请提供收货人姓名、联系电话和详细收货地址，例如：Zhang，0412345678，UNSW High Street, Kensington NSW 2052。",
                OrderManageStateKeys.NEED_USER_INPUT,
                true
        );
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
        ConversationRuntimeContext context = state.value(
                GuideGraphStateKeys.CONVERSATION_CONTEXT,
                ConversationRuntimeContext.class
        ).orElse(null);
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
