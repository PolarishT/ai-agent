package com.bytedance.ai.graph.cartmanage.subgraph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.cartmanage.CartManageSlots;
import com.bytedance.ai.graph.cartmanage.application.CartManageSlotFillingService;
import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.conversation.context.ConversationRuntimeContext;
import com.bytedance.ai.graph.cartmanage.subgraph.CartAction;
import com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys;
import com.bytedance.ai.graph.cartmanage.subgraph.CartWorkflowStatus;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartActionParser;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartGraphStateSupport;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartItemLookup;
import com.bytedance.ai.graph.intent.support.SlotKeys;
import com.bytedance.ai.graph.orchestration.GuideGraphStateKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 购物车管理子图节点，解析用户要操作的商品目标和数量。
 */
public class CartResolveTargetNode {

    private static final Logger log = LoggerFactory.getLogger(CartResolveTargetNode.class);

    private final CartManageSlotFillingService slotFillingService;
    private final CartItemLookup cartItemLookup;
    private final CatalogQueryFacade catalogQueryFacade;

    public CartResolveTargetNode(
            CartManageSlotFillingService slotFillingService,
            CartItemLookup cartItemLookup,
            CatalogQueryFacade catalogQueryFacade
    ) {
        this.slotFillingService = slotFillingService;
        this.cartItemLookup = cartItemLookup;
        this.catalogQueryFacade = catalogQueryFacade;
    }

    public Map<String, Object> apply(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        String userId = CartGraphStateSupport.requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = CartGraphStateSupport.requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        String userMessage = state.value(GuideGraphStateKeys.MESSAGE, "");
        Map<String, Object> intentSlots = CartGraphStateSupport.readIntentSlots(state);

        CartAction action = CartActionParser.safeCartAction(
                state.value(CartGraphStateKeys.CART_ACTION, CartAction.UNKNOWN.name())
        );
        log.info("Cart resolve target start: action={}, userId={}, conversationId={}",
                action, userId, conversationId);

        CartManageSlots filledSlots = slotFillingService.extract(userMessage, CartGraphStateSupport.conversationMemory(state));
        String productName = CartGraphStateSupport.firstNonBlank(
                CartGraphStateSupport.asString(intentSlots.get(SlotKeys.PRODUCT_NAME)),
                filledSlots.productName()
        );
        String productId = CartGraphStateSupport.firstNonBlank(
                CartGraphStateSupport.asString(intentSlots.get(SlotKeys.PRODUCT_ID)),
                CartGraphStateSupport.asString(intentSlots.get(SlotKeys.PRODUCT_REF)),
                filledSlots.productId()
        );
        String skuId = CartGraphStateSupport.firstNonBlank(
                CartGraphStateSupport.asString(intentSlots.get(SlotKeys.SKU_ID)),
                filledSlots.skuId()
        );
        BigDecimal expectedPrice = CartGraphStateSupport.firstNonNull(
                CartGraphStateSupport.asBigDecimal(intentSlots.get(SlotKeys.EXPECTED_PRICE)),
                filledSlots.expectedPrice(),
                CartGraphStateSupport.extractExpectedPrice(userMessage)
        );
        Integer quantity = CartGraphStateSupport.firstNonNull(
                CartGraphStateSupport.asInteger(intentSlots.get(SlotKeys.QUANTITY)),
                filledSlots.quantity()
        );
        Integer itemIndex = CartGraphStateSupport.firstNonNull(
                CartGraphStateSupport.asInteger(intentSlots.get(SlotKeys.ITEM_INDEX)),
                filledSlots.itemIndex()
        );
        Boolean contextualReference = CartGraphStateSupport.firstNonNull(
                Boolean.TRUE.equals(intentSlots.get(SlotKeys.CONTEXTUAL_REFERENCE)) ? Boolean.TRUE : null,
                filledSlots.contextualReference()
        );

        if (quantity == null || quantity < 1) {
            quantity = 1;
        }

        if (action == CartAction.ADD && (!StringUtils.hasText(productId) || !StringUtils.hasText(skuId))) {
            ConversationRuntimeContext.ProductCandidateItem contextCandidate =
                    resolveAddCandidateFromContext(state, itemIndex, productName, contextualReference);
            if (contextCandidate != null) {
                productId = CartGraphStateSupport.firstNonBlank(productId, contextCandidate.productId());
                skuId = CartGraphStateSupport.firstNonBlank(skuId, contextCandidate.skuId());
                productName = CartGraphStateSupport.firstNonBlank(productName, contextCandidate.productName());
                expectedPrice = CartGraphStateSupport.firstNonNull(expectedPrice, contextCandidate.price());
            }
            if (StringUtils.hasText(productId) && !StringUtils.hasText(skuId)) {
                skuId = firstActiveSkuId(productId);
            }
        }

        updates.put(CartGraphStateKeys.PRODUCT_NAME, productName);
        updates.put(CartGraphStateKeys.PRODUCT_ID, productId);
        updates.put(CartGraphStateKeys.SKU_ID, skuId);
        updates.put(CartGraphStateKeys.EXPECTED_PRICE, expectedPrice);
        updates.put(CartGraphStateKeys.QUANTITY, quantity);
        updates.put(CartGraphStateKeys.ITEM_INDEX, itemIndex);
        updates.put(CartGraphStateKeys.CONTEXTUAL_REFERENCE, contextualReference);

        if ((action == CartAction.REMOVE || action == CartAction.UPDATE_QUANTITY)
                && !cartItemLookup.hasTarget(itemIndex, productName, productId, skuId)) {
            updates.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.WAITING_CLARIFICATION.name());
            updates.put(CartGraphStateKeys.NODE_MESSAGE,
                    "请说明要操作购物车中的第几个商品，例如“删除第 1 个”或“把第 2 个改成 3 件”。");
            updates.put(CartGraphStateKeys.NEED_USER_INPUT, true);
        }

        log.info("Resolved target - productName: {}, productId: {}, skuId: {}, expectedPrice: {}, quantity: {}, itemIndex: {}",
                productName, productId, skuId, expectedPrice, quantity, itemIndex);
        log.info("Cart resolve target done: action={}, hasTarget={}, status={}, needUserInput={}",
                action,
                cartItemLookup.hasTarget(itemIndex, productName, productId, skuId),
                updates.get(CartGraphStateKeys.WORKFLOW_STATUS),
                updates.get(CartGraphStateKeys.NEED_USER_INPUT));
        return updates;
    }

    private ConversationRuntimeContext.ProductCandidateItem resolveAddCandidateFromContext(
            OverAllState state,
            Integer itemIndex,
            String productName,
            Boolean contextualReference
    ) {
        ConversationRuntimeContext context = state.value(
                GuideGraphStateKeys.CONVERSATION_CONTEXT,
                ConversationRuntimeContext.class
        ).orElse(null);
        if (context == null) {
            return null;
        }
        if (itemIndex != null && itemIndex > 0) {
            return context.productCandidates().stream()
                    .filter(candidate -> candidate.rank() == itemIndex)
                    .findFirst()
                    .orElse(null);
        }
        if (Boolean.TRUE.equals(contextualReference)) {
            ConversationRuntimeContext.ProductCandidateItem focused = candidateFromFocus(context);
            if (focused != null) {
                return focused;
            }
        }
        if (StringUtils.hasText(productName)) {
            String expected = CartGraphStateSupport.normalizeMatchText(productName);
            return context.productCandidates().stream()
                    .filter(candidate -> {
                        String actual = CartGraphStateSupport.normalizeMatchText(candidate.productName());
                        return StringUtils.hasText(actual)
                                && (actual.contains(expected) || expected.contains(actual));
                    })
                    .findFirst()
                    .orElseGet(() -> context.productCandidates().size() == 1
                            && weakProductNameMatch(productName, context.productCandidates().getFirst().productName())
                            ? context.productCandidates().getFirst()
                            : null);
        }
        return context.productCandidates().size() == 1 ? context.productCandidates().getFirst() : null;
    }

    private ConversationRuntimeContext.ProductCandidateItem candidateFromFocus(ConversationRuntimeContext context) {
        ConversationRuntimeContext.Focus focus = context.focus();
        if (focus == null) {
            return null;
        }
        if (focus.rank() != null) {
            return context.productCandidates().stream()
                    .filter(candidate -> candidate.rank() == focus.rank())
                    .findFirst()
                    .orElse(null);
        }
        if (StringUtils.hasText(focus.productId())) {
            return context.productCandidates().stream()
                    .filter(candidate -> focus.productId().equals(candidate.productId()))
                    .findFirst()
                    .orElse(new ConversationRuntimeContext.ProductCandidateItem(
                            null,
                            1,
                            focus.productId(),
                            focus.skuId(),
                            focus.productName(),
                            null,
                            null,
                            null,
                            focus.productId(),
                            null,
                            focus.payload()
                    ));
        }
        return null;
    }

    private boolean weakProductNameMatch(String requested, String candidateName) {
        String expected = CartGraphStateSupport.normalizeMatchText(requested)
                .replace("的", "");
        String actual = CartGraphStateSupport.normalizeMatchText(candidateName)
                .replace("的", "");
        if (!StringUtils.hasText(expected) || !StringUtils.hasText(actual)) {
            return false;
        }
        int matched = 0;
        for (int i = 0; i < expected.length(); i++) {
            if (actual.indexOf(expected.charAt(i)) >= 0) {
                matched++;
            }
        }
        return matched >= Math.min(4, expected.length());
    }

    private String firstActiveSkuId(String productId) {
        if (catalogQueryFacade == null || !StringUtils.hasText(productId)) {
            return null;
        }
        try {
            Long id = Long.valueOf(productId);
            return catalogQueryFacade.listSkus(id).stream()
                    .filter(sku -> "ACTIVE".equals(sku.status()))
                    .filter(sku -> sku.stock() == null || sku.stock() > 0)
                    .findFirst()
                    .map(CatalogSkuView::id)
                    .map(String::valueOf)
                    .orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public String routeAfter(OverAllState state) {
        CartAction action = CartActionParser.safeCartAction(
                state.value(CartGraphStateKeys.CART_ACTION, CartAction.UNKNOWN.name())
        );

        String productId = state.<String>value(CartGraphStateKeys.PRODUCT_ID).orElse(null);
        String skuId = state.<String>value(CartGraphStateKeys.SKU_ID).orElse(null);

        String route = "FINAL";
        if (StringUtils.hasText(productId) && StringUtils.hasText(skuId)) {
            route = "HAS_IDS";
        } else if (action == CartAction.ADD) {
            String productName = state.value(CartGraphStateKeys.PRODUCT_NAME, "");
            if (StringUtils.hasText(productName)) {
                route = "ADD_SEARCH";
            }
        } else if (action == CartAction.REMOVE || action == CartAction.UPDATE_QUANTITY) {
            Integer itemIndex = state.<Integer>value(CartGraphStateKeys.ITEM_INDEX).orElse(null);
            String productName = state.value(CartGraphStateKeys.PRODUCT_NAME, "");
            if (cartItemLookup.hasTarget(itemIndex, productName, productId, skuId)) {
                route = "REMOVE_UPDATE_EXECUTE";
            }
        }

        log.info("Cart route after resolve target: action={}, productId={}, skuId={}, route={}",
                action, productId, skuId, route);
        return route;
    }
}
