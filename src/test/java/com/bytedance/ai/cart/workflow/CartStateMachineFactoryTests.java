package com.bytedance.ai.cart.workflow;

import com.bytedance.ai.cart.api.CartState;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.StateMachineEventResult;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class CartStateMachineFactoryTests {

    private final CartStateMachineFactory factory = new CartStateMachineFactory();

    @Test
    void addItemCanMoveIdleCartIntoInCart() {
        StateMachine<CartState, CartEvent> machine = factory.create(CartState.IDLE);

        machine.startReactively().block();
        StateMachineEventResult<CartState, CartEvent> result = machine
                .sendEvent(Mono.just(MessageBuilder.withPayload(CartEvent.CONFIRM_ADD).build()))
                .blockLast();

        assertThat(result).isNotNull();
        assertThat(result.getResultType()).isEqualTo(StateMachineEventResult.ResultType.ACCEPTED);
        assertThat(machine.getState().getId()).isEqualTo(CartState.IN_CART);
        machine.stopReactively().block();
    }

    @Test
    void checkoutRequiresTwoStepTransitionBeforePlaced() {
        StateMachine<CartState, CartEvent> machine = factory.create(CartState.IN_CART);

        machine.startReactively().block();
        machine.sendEvent(Mono.just(MessageBuilder.withPayload(CartEvent.CHECKOUT).build())).blockLast();
        assertThat(machine.getState().getId()).isEqualTo(CartState.CHECKING_OUT);
        machine.sendEvent(Mono.just(MessageBuilder.withPayload(CartEvent.CHECKOUT).build())).blockLast();
        assertThat(machine.getState().getId()).isEqualTo(CartState.PLACED);
        machine.stopReactively().block();
    }

    @Test
    void placedCartRejectsRemoveEvent() {
        StateMachine<CartState, CartEvent> machine = factory.create(CartState.PLACED);

        machine.startReactively().block();
        StateMachineEventResult<CartState, CartEvent> result = machine
                .sendEvent(Mono.just(MessageBuilder.withPayload(CartEvent.REMOVE).build()))
                .blockLast();

        assertThat(result).isNotNull();
        assertThat(result.getResultType()).isEqualTo(StateMachineEventResult.ResultType.DENIED);
        assertThat(machine.getState().getId()).isEqualTo(CartState.PLACED);
        machine.stopReactively().block();
    }
}
