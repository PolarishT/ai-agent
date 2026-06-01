package com.bytedance.ai.graph.cartmanage.subgraph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.cartmanage.subgraph.CartAction;
import com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartActionParser;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartGraphStateSupport;
import com.bytedance.ai.graph.intent.support.SlotKeys;
import com.bytedance.ai.graph.orchestration.GuideGraphStateKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 购物车管理子图节点，识别本轮购物车动作类型。
 */
public class CartResolveActionNode {

    private static final Logger log = LoggerFactory.getLogger(CartResolveActionNode.class);

    public Map<String, Object> apply(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        String userMessage = state.value(GuideGraphStateKeys.MESSAGE, "");
        Map<String, Object> intentSlots = CartGraphStateSupport.readIntentSlots(state);

        CartAction action = CartAction.UNKNOWN;
        boolean pendingSelection = state.value(CartGraphStateKeys.PENDING_CART_ACTION_ID).isPresent();
        log.info("Cart resolve action start: pendingSelection={}, slotKeys={}",
                pendingSelection, intentSlots.keySet());

        if (pendingSelection) {
            action = CartAction.CONFIRM;
        } else {
            String mainCartAction = CartGraphStateSupport.asString(intentSlots.get(SlotKeys.CART_ACTION));
            if (StringUtils.hasText(mainCartAction)) {
                action = CartActionParser.parseCartAction(mainCartAction);
            }
            if (action == CartAction.UNKNOWN) {
                action = CartActionParser.inferActionFromMessage(userMessage);
            }
        }

        updates.put(CartGraphStateKeys.CART_ACTION, action.name());
        log.info("Cart resolve action done: action={}", action);
        return updates;
    }

    public String routeAfter(OverAllState state) {
        String actionName = state.value(CartGraphStateKeys.CART_ACTION, CartAction.UNKNOWN.name());
        CartAction action = CartActionParser.safeCartAction(actionName);
        String route;

        if (action == CartAction.VIEW) {
            route = "VIEW";
        } else if (action == CartAction.CLEAR) {
            route = "CLEAR";
        } else if (action == CartAction.CONFIRM) {
            route = "CONFIRM";
        } else if (action == CartAction.ADD || action == CartAction.REMOVE || action == CartAction.UPDATE_QUANTITY) {
            route = "ADD_REMOVE_UPDATE";
        } else {
            route = "UNKNOWN";
        }
        log.info("Cart route after resolve action: action={}, route={}", action, route);
        return route;
    }
}
