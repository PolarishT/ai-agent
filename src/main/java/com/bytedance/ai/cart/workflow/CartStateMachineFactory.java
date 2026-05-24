package com.bytedance.ai.cart.workflow;

import com.bytedance.ai.cart.api.CartState;
import java.util.EnumSet;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineBuilder;
import org.springframework.stereotype.Component;

@Component
public class CartStateMachineFactory {

    public StateMachine<CartState, CartEvent> create(CartState initialState) {
        try {
            StateMachineBuilder.Builder<CartState, CartEvent> builder = StateMachineBuilder.builder();
            builder.configureConfiguration()
                    .withConfiguration()
                    .autoStartup(false);
            builder.configureStates()
                    .withStates()
                    .initial(initialState == null ? CartState.IDLE : initialState)
                    .states(EnumSet.allOf(CartState.class));
            builder.configureTransitions()
                    .withExternal().source(CartState.IDLE).target(CartState.ITEM_PROPOSED).event(CartEvent.PROPOSE_ITEM)
                    .and()
                    .withExternal().source(CartState.IN_CART).target(CartState.ITEM_PROPOSED).event(CartEvent.PROPOSE_ITEM)
                    .and()
                    .withExternal().source(CartState.ITEM_PROPOSED).target(CartState.IN_CART).event(CartEvent.CONFIRM_ADD)
                    .and()
                    .withExternal().source(CartState.IDLE).target(CartState.IN_CART).event(CartEvent.CONFIRM_ADD)
                    .and()
                    .withExternal().source(CartState.IN_CART).target(CartState.IN_CART).event(CartEvent.CONFIRM_ADD)
                    .and()
                    .withExternal().source(CartState.IN_CART).target(CartState.IN_CART).event(CartEvent.UPDATE_QTY)
                    .and()
                    .withExternal().source(CartState.IN_CART).target(CartState.IN_CART).event(CartEvent.REMOVE)
                    .and()
                    .withExternal().source(CartState.IN_CART).target(CartState.CHECKING_OUT).event(CartEvent.CHECKOUT)
                    .and()
                    .withExternal().source(CartState.CHECKING_OUT).target(CartState.PLACED).event(CartEvent.CHECKOUT)
                    .and()
                    .withExternal().source(CartState.ITEM_PROPOSED).target(CartState.CANCELLED).event(CartEvent.CANCEL)
                    .and()
                    .withExternal().source(CartState.IN_CART).target(CartState.CANCELLED).event(CartEvent.CANCEL)
                    .and()
                    .withExternal().source(CartState.CHECKING_OUT).target(CartState.CANCELLED).event(CartEvent.CANCEL);
            return builder.build();
        } catch (Exception exception) {
            throw new CartWorkflowException("创建购物车状态机失败", exception);
        }
    }
}
