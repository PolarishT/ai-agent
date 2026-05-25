package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.AgentTurnRequest;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.application.AgentSseEventFactory;
import com.bytedance.ai.agent.memory.ConversationMemoryLoader;
import com.bytedance.ai.agent.memory.ConversationSummarizer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EcommerceWorkflowEngineEntryNodeTests {

    private final ResumeInputParser resumeInputParser = new ResumeInputParser();
    private final EcommerceWorkflowEngine engine = new EcommerceWorkflowEngine(
            mock(WorkflowDefinitionLoader.class),
            mock(WorkflowStateStore.class),
            mock(NodeExecutorRegistry.class),
            mock(EdgeResolver.class),
            mock(ConversationMemoryLoader.class),
            mock(ConversationSummarizer.class),
            new AgentSseEventFactory(),
            resumeInputParser
    );

    @Test
    void freshConversationStartsAtDefinitionStart() {
        WorkflowDefinition definition = simpleDefinition();
        WorkflowRuntimeState state = new WorkflowRuntimeState();

        String entry = engine.resolveEntryNode(definition, state, requestFor("找洗面奶"));

        assertThat(entry).isEqualTo("intent_router");
    }

    @Test
    void waitingSelectionResumesAtPendingResumeNode() {
        WorkflowDefinition definition = simpleDefinition();
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        state.status(WorkflowStatus.WAITING_SELECTION);
        state.currentNode("inventory_check");
        SpuCardView c1 = card(1L, "ext-1");
        SpuCardView c2 = card(2L, "ext-2");
        state.cards(List.of(c1, c2));
        state.pendingSelection(new PendingSelection(
                "spu", "inventory_check", "inventory_check",
                List.of(c1, c2), null, null));

        String entry = engine.resolveEntryNode(definition, state, requestFor("第二个"));

        assertThat(entry).isEqualTo("inventory_check");
        assertThat(state.status()).isEqualTo(WorkflowStatus.RUNNING);
        // pendingSelection is preserved through resume so the executor can read selectedExternalRef.
        // The executor is responsible for clearing it once consumed.
        assertThat(state.pendingSelection()).isNotNull();
        assertThat(state.pendingSelection().selectedExternalRef()).isEqualTo("ext-2");
    }

    @Test
    void waitingConfirmationWithoutConfirmReturnsNullEntry() {
        WorkflowDefinition definition = simpleDefinition();
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        state.status(WorkflowStatus.WAITING_CONFIRMATION);
        state.currentNode("order_create");
        state.pendingConfirmation(new PendingConfirmation(
                "ORDER_CREATE", "order_create", "order_create", Map.of(), false));

        // user says something unrelated — parser leaves status WAITING_CONFIRMATION.
        String entry = engine.resolveEntryNode(definition, state, requestFor("帮我看看天气"));

        assertThat(entry).isNull();
        assertThat(state.status()).isEqualTo(WorkflowStatus.WAITING_CONFIRMATION);
    }

    @Test
    void waitingConfirmationConfirmedResumesAtOrderCreate() {
        WorkflowDefinition definition = simpleDefinition();
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        state.status(WorkflowStatus.WAITING_CONFIRMATION);
        state.currentNode("order_create");
        state.pendingConfirmation(new PendingConfirmation(
                "ORDER_CREATE", "order_create", "order_create", Map.of(), false));

        String entry = engine.resolveEntryNode(definition, state, requestFor("确认下单"));

        assertThat(entry).isEqualTo("order_create");
        assertThat(state.status()).isEqualTo(WorkflowStatus.RUNNING);
    }

    private WorkflowDefinition simpleDefinition() {
        WorkflowDefinition.WorkflowNodeDefinition start = new WorkflowDefinition.WorkflowNodeDefinition(
                "intent_router", "intent_router", null, null, Map.of(), Map.of(), null, null);
        WorkflowDefinition.WorkflowNodeDefinition inv = new WorkflowDefinition.WorkflowNodeDefinition(
                "inventory_check", "inventory_check", null, null, Map.of(), Map.of(),
                "WAITING_SELECTION", "inventory_check");
        WorkflowDefinition.WorkflowNodeDefinition order = new WorkflowDefinition.WorkflowNodeDefinition(
                "order_create", "order_create", null, null, Map.of(), Map.of(),
                "WAITING_CONFIRMATION", "order_create");
        return new WorkflowDefinition("ecommerce-guide-v1", "intent_router",
                List.of(start, inv, order), List.of());
    }

    private EcommerceWorkflowEngine.WorkflowRequest requestFor(String message) {
        AgentTurnRequest req = new AgentTurnRequest("u", "c", message, "t", null, null, List.of());
        return new EcommerceWorkflowEngine.WorkflowRequest(req, "t", "corr", null);
    }

    private SpuCardView card(Long spuId, String externalRef) {
        return new SpuCardView(spuId, externalRef, "title-" + spuId, "brand", null,
                BigDecimal.ZERO, BigDecimal.ZERO, 1, 0.0, List.of(), List.of(), "#" + spuId);
    }
}
