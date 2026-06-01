package com.bytedance.ai.graph.cartmanage.subgraph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.cartmanage.subgraph.CartAction;
import com.bytedance.ai.graph.cartmanage.subgraph.CartGraphStateKeys;
import com.bytedance.ai.graph.cartmanage.subgraph.CartWorkflowStatus;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartActionParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 购物车管理子图节点，根据执行结果组装最终回复和工作流状态。
 */
public class CartFinalResponseNode {

    private static final Logger log = LoggerFactory.getLogger(CartFinalResponseNode.class);

    public Map<String, Object> apply(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        log.info("Cart final response start: existingStatus={}, existingNeedUserInput={}, hasMessage={}",
                state.value(CartGraphStateKeys.WORKFLOW_STATUS).map(Object::toString).orElse(null),
                state.value(CartGraphStateKeys.NEED_USER_INPUT).orElse(null),
                state.value(CartGraphStateKeys.NODE_MESSAGE).isPresent());

        if (!state.value(CartGraphStateKeys.WORKFLOW_STATUS).isPresent()) {
            CartAction action = CartActionParser.safeCartAction(
                    state.value(CartGraphStateKeys.CART_ACTION, CartAction.UNKNOWN.name())
            );
            updates.put(CartGraphStateKeys.WORKFLOW_STATUS,
                    switch (action) {
                        case ADD, REMOVE, UPDATE_QUANTITY -> CartWorkflowStatus.WAITING_CLARIFICATION.name();
                        default -> CartWorkflowStatus.FAILED.name();
                    });
        }
        if (!state.value(CartGraphStateKeys.NODE_MESSAGE).isPresent()) {
            updates.put(CartGraphStateKeys.NODE_MESSAGE, fallbackCartMessage(state));
        }
        if (!state.value(CartGraphStateKeys.NEED_USER_INPUT).isPresent()) {
            String status = state.value(CartGraphStateKeys.WORKFLOW_STATUS)
                    .map(Object::toString)
                    .orElseGet(() -> String.valueOf(updates.get(CartGraphStateKeys.WORKFLOW_STATUS)));
            updates.put(CartGraphStateKeys.NEED_USER_INPUT,
                    CartWorkflowStatus.WAITING_CLARIFICATION.name().equals(status)
                            || CartWorkflowStatus.WAITING_USER_SELECTION.name().equals(status));
        }

        log.info("Cart final response done: status={}, needUserInput={}, messageSource={}",
                state.value(CartGraphStateKeys.WORKFLOW_STATUS)
                        .map(Object::toString)
                        .orElseGet(() -> String.valueOf(updates.get(CartGraphStateKeys.WORKFLOW_STATUS))),
                state.value(CartGraphStateKeys.NEED_USER_INPUT)
                        .map(Object::toString)
                        .orElseGet(() -> String.valueOf(updates.get(CartGraphStateKeys.NEED_USER_INPUT))),
                state.value(CartGraphStateKeys.NODE_MESSAGE).isPresent() ? "existing" : "fallback");
        return updates;
    }

    private String fallbackCartMessage(OverAllState state) {
        CartAction action = CartActionParser.safeCartAction(
                state.value(CartGraphStateKeys.CART_ACTION, CartAction.UNKNOWN.name())
        );
        return switch (action) {
            case REMOVE, UPDATE_QUANTITY -> "请说明要操作购物车中的第几个商品，例如“删除第 1 个”或“把第 2 个改成 3 件”。";
            default -> "我还缺少必要信息，无法完成这次购物车操作。";
        };
    }
}
