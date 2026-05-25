package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.api.SpuCardView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeInputParserTests {

    private final ResumeInputParser parser = new ResumeInputParser();

    @Test
    void waitingSelectionResolvesChineseOrdinal() {
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        state.status(WorkflowStatus.WAITING_SELECTION);
        state.pendingSelection(new PendingSelection(
                "spu",
                "inventory_check",
                "inventory_check",
                List.of(card(1L, "ext-1"), card(2L, "ext-2"), card(3L, "ext-3")),
                null,
                null
        ));

        parser.apply(state, "第二个");

        assertThat(state.pendingSelection().resolved()).isTrue();
        assertThat(state.pendingSelection().selectedExternalRef()).isEqualTo("ext-2");
        assertThat(state.pendingSelection().selectedSkuId()).isEqualTo(2L);
        assertThat(state.status()).isEqualTo(WorkflowStatus.RUNNING);
    }

    @Test
    void waitingSelectionIgnoresUnparseableInput() {
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        state.status(WorkflowStatus.WAITING_SELECTION);
        state.pendingSelection(new PendingSelection(
                "spu", "inventory_check", "inventory_check",
                List.of(card(1L, "ext-1")), null, null));

        parser.apply(state, "顺便问一下天气");

        assertThat(state.pendingSelection().resolved()).isFalse();
        assertThat(state.status()).isEqualTo(WorkflowStatus.WAITING_SELECTION);
    }

    @Test
    void waitingConfirmationFlipsConfirmed() {
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        state.status(WorkflowStatus.WAITING_CONFIRMATION);
        state.pendingConfirmation(new PendingConfirmation(
                "ORDER_CREATE", "order_create", "order_create", null, false));

        parser.apply(state, "确认下单");

        assertThat(state.pendingConfirmation().confirmed()).isTrue();
        assertThat(state.status()).isEqualTo(WorkflowStatus.RUNNING);
    }

    @Test
    void waitingConfirmationCancelEndsTurn() {
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        state.status(WorkflowStatus.WAITING_CONFIRMATION);
        state.pendingConfirmation(new PendingConfirmation(
                "ORDER_CREATE", "order_create", "order_create", null, false));

        parser.apply(state, "算了取消吧");

        assertThat(state.pendingConfirmation().confirmed()).isFalse();
        assertThat(state.status()).isEqualTo(WorkflowStatus.END);
    }

    @Test
    void waitingSlotMergesPriceRange() {
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        state.status(WorkflowStatus.WAITING_SLOT);

        parser.apply(state, "预算100以内");

        assertThat(state.slots().priceRange()).isNotNull();
        assertThat(state.slots().priceRange().max()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(state.status()).isEqualTo(WorkflowStatus.RUNNING);
    }

    private SpuCardView card(Long spuId, String externalRef) {
        return new SpuCardView(spuId, externalRef, "title-" + spuId, "brand", null,
                BigDecimal.ZERO, BigDecimal.ZERO, 1, 0.0, List.of(), List.of(), "#" + spuId);
    }
}
