package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 snapshot 能完整往返 — JdbcWorkflowStateStore 的恢复路径依赖它。
 */
class WorkflowRuntimeStateSnapshotTests {

    private final RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());

    @Test
    void roundTripPreservesPauseState() {
        WorkflowRuntimeState original = new WorkflowRuntimeState();
        original.status(WorkflowStatus.WAITING_CONFIRMATION);
        original.intent(IntentType.CART_OP);
        original.currentNode("order_create");
        original.userId("u-1");
        original.cards(List.of(card(7L, "ext-7")));
        original.pendingSelection(new PendingSelection(
                "spu", "inventory_check", "inventory_check",
                List.of(card(7L, "ext-7")), "ext-7", 7L));
        original.pendingConfirmation(new PendingConfirmation(
                "ORDER_CREATE", "order_create", "order_create",
                Map.of("externalRef", "ext-7"), false));

        WorkflowRuntimeStateSnapshot snapshot = WorkflowRuntimeStateSnapshot.from(original);
        String json = jsonCodec.write(snapshot);
        WorkflowRuntimeStateSnapshot restored = jsonCodec.read(json, WorkflowRuntimeStateSnapshot.class);
        WorkflowRuntimeState back = restored.toRuntimeState();

        assertThat(back.status()).isEqualTo(WorkflowStatus.WAITING_CONFIRMATION);
        assertThat(back.intent()).isEqualTo(IntentType.CART_OP);
        assertThat(back.currentNode()).isEqualTo("order_create");
        assertThat(back.cards()).hasSize(1);
        assertThat(back.cards().getFirst().externalRef()).isEqualTo("ext-7");
        assertThat(back.pendingSelection()).isNotNull();
        assertThat(back.pendingSelection().selectedExternalRef()).isEqualTo("ext-7");
        assertThat(back.pendingSelection().selectedSkuId()).isEqualTo(7L);
        assertThat(back.pendingConfirmation()).isNotNull();
        assertThat(back.pendingConfirmation().confirmed()).isFalse();
        assertThat(back.pendingConfirmation().type()).isEqualTo("ORDER_CREATE");
    }

    @Test
    void emptyStateRoundTrips() {
        WorkflowRuntimeStateSnapshot snapshot = WorkflowRuntimeStateSnapshot.from(new WorkflowRuntimeState());
        String json = jsonCodec.write(snapshot);
        WorkflowRuntimeStateSnapshot restored = jsonCodec.read(json, WorkflowRuntimeStateSnapshot.class);
        WorkflowRuntimeState back = restored.toRuntimeState();

        assertThat(back.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(back.cards()).isEmpty();
        assertThat(back.pendingSelection()).isNull();
        assertThat(back.pendingConfirmation()).isNull();
        assertThat(back.pendingSlot()).isNull();
        assertThat(back.slots()).isEqualTo(Slot.empty());
    }

    private SpuCardView card(Long spuId, String externalRef) {
        return new SpuCardView(spuId, externalRef, "title-" + spuId, "brand", null,
                BigDecimal.ZERO, BigDecimal.ZERO, 1, 0.0, List.of(), List.of(), "#" + spuId);
    }
}
