package com.bytedance.ai.graph.conversation.context;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedTaskChainPlannerTest {

    private final TaskChainCoordinator coordinator = new TaskChainCoordinator(noOpManager());
    private final RuleBasedTaskChainPlanner planner = new RuleBasedTaskChainPlanner(coordinator);

    @Test
    void newChainWhenNoActiveChain() {
        TaskChainPlanner.PlanResult result = planner.plan(input(
                "PRODUCT_SEARCH", "product_query_workflow", true, "找防晒霜", emptyContext()));

        assertThat(result.previousChainIdToCancel()).isNull();
        ConversationRuntimeContext.TaskChain chain = result.chainToPersist();
        assertThat(chain).isNotNull();
        assertThat(chain.taskChainId()).startsWith("chain_");
        assertThat(chain.status()).isEqualTo("EXECUTING");
        assertThat(chain.steps()).hasSize(1);
        assertThat(chain.steps().get(0).stepNo()).isEqualTo(1);
        assertThat(chain.steps().get(0).status()).isEqualTo("SUCCEEDED");
        assertThat(chain.steps().get(0).taskType()).isEqualTo("PRODUCT_SEARCH");
    }

    @Test
    void appendsStepWhenIntentContinuesActiveChain() {
        ConversationRuntimeContext.TaskChain existing = chainWithLastStep(
                "chain-A", "PRODUCT_SEARCH", "SUCCEEDED", "EXECUTING");
        TaskChainPlanner.PlanResult result = planner.plan(input(
                "ADD_TO_CART", "cart_manage_workflow", true, "加进购物车",
                contextWith(existing)));

        assertThat(result.previousChainIdToCancel()).isNull();
        ConversationRuntimeContext.TaskChain chain = result.chainToPersist();
        assertThat(chain.taskChainId()).isEqualTo("chain-A"); // 同一条链
        assertThat(chain.steps()).hasSize(2);
        assertThat(chain.steps().get(1).stepNo()).isEqualTo(2);
        assertThat(chain.steps().get(1).taskType()).isEqualTo("ADD_TO_CART");
        assertThat(chain.steps().get(1).workflow()).isEqualTo("cart_manage_workflow");
        assertThat(chain.steps().get(1).status()).isEqualTo("SUCCEEDED");
        assertThat(chain.status()).isEqualTo("EXECUTING");
    }

    @Test
    void cartCanTransitionToOrder() {
        ConversationRuntimeContext.TaskChain existing = chainWithLastStep(
                "chain-A", "ADD_TO_CART", "SUCCEEDED", "EXECUTING");
        TaskChainPlanner.PlanResult result = planner.plan(input(
                "CREATE_ORDER", "order_manage_workflow", true, "下单",
                contextWith(existing)));

        assertThat(result.previousChainIdToCancel()).isNull();
        assertThat(result.chainToPersist().taskChainId()).isEqualTo("chain-A");
        assertThat(result.chainToPersist().steps()).hasSize(2);
    }

    @Test
    void cancelsAndStartsNewWhenIntentIsIncompatible() {
        // ORDER → PRODUCT 不允许（不允许反向跳）
        ConversationRuntimeContext.TaskChain existing = chainWithLastStep(
                "chain-A", "CONFIRM_ORDER", "SUCCEEDED", "EXECUTING");
        TaskChainPlanner.PlanResult result = planner.plan(input(
                "PRODUCT_SEARCH", "product_query_workflow", true, "再找点别的",
                contextWith(existing)));

        assertThat(result.previousChainIdToCancel()).isEqualTo("chain-A");
        assertThat(result.chainToPersist().taskChainId()).isNotEqualTo("chain-A");
        assertThat(result.chainToPersist().steps()).hasSize(1);
        assertThat(result.chainToPersist().steps().get(0).taskType()).isEqualTo("PRODUCT_SEARCH");
    }

    @Test
    void productCannotJumpDirectlyToOrder() {
        // PRODUCT → ORDER 不允许（必须经过 CART）
        ConversationRuntimeContext.TaskChain existing = chainWithLastStep(
                "chain-A", "PRODUCT_SEARCH", "SUCCEEDED", "EXECUTING");
        TaskChainPlanner.PlanResult result = planner.plan(input(
                "CREATE_ORDER", "order_manage_workflow", true, "下单",
                contextWith(existing)));

        assertThat(result.previousChainIdToCancel()).isEqualTo("chain-A");
        assertThat(result.chainToPersist().taskChainId()).isNotEqualTo("chain-A");
    }

    @Test
    void metaIntentReturnsNullResultAndDoesNotTouchChains() {
        ConversationRuntimeContext.TaskChain existing = chainWithLastStep(
                "chain-A", "PRODUCT_SEARCH", "SUCCEEDED", "EXECUTING");
        TaskChainPlanner.PlanResult result = planner.plan(input(
                "CLARIFY", "clarify_workflow", true, "?",
                contextWith(existing)));

        assertThat(result.chainToPersist()).isNull();
        assertThat(result.previousChainIdToCancel()).isNull();
    }

    @Test
    void doesNotContinueWhenLastStepFailed() {
        ConversationRuntimeContext.TaskChain existing = chainWithLastStep(
                "chain-A", "PRODUCT_SEARCH", "FAILED", "EXECUTING");
        TaskChainPlanner.PlanResult result = planner.plan(input(
                "PRODUCT_RECOMMEND", "product_query_workflow", true, "再试试",
                contextWith(existing)));

        assertThat(result.previousChainIdToCancel()).isEqualTo("chain-A");
        assertThat(result.chainToPersist().taskChainId()).isNotEqualTo("chain-A");
    }

    @Test
    void doesNotConsiderNonExecutingChainsAsActive() {
        ConversationRuntimeContext.TaskChain succeeded = chainWithLastStep(
                "chain-A", "PRODUCT_SEARCH", "SUCCEEDED", "SUCCEEDED");
        ConversationRuntimeContext.TaskChain cancelled = chainWithLastStep(
                "chain-B", "ADD_TO_CART", "CANCELLED", "CANCELLED");

        TaskChainPlanner.PlanResult result = planner.plan(input(
                "ADD_TO_CART", "cart_manage_workflow", true, "加车",
                contextWith(succeeded, cancelled)));

        assertThat(result.previousChainIdToCancel()).isNull();
        assertThat(result.chainToPersist().taskChainId()).isNotIn("chain-A", "chain-B");
    }

    @Test
    void failedTurnMarksStepAndChainAsFailed() {
        TaskChainPlanner.PlanResult result = planner.plan(input(
                "PRODUCT_SEARCH", "product_query_workflow", false, "查不到", emptyContext()));

        assertThat(result.chainToPersist().status()).isEqualTo("FAILED");
        assertThat(result.chainToPersist().steps().get(0).status()).isEqualTo("FAILED");
    }

    @Test
    void failedAppendedStepMarksChainAsFailed() {
        ConversationRuntimeContext.TaskChain existing = chainWithLastStep(
                "chain-A", "PRODUCT_SEARCH", "SUCCEEDED", "EXECUTING");
        TaskChainPlanner.PlanResult result = planner.plan(input(
                "ADD_TO_CART", "cart_manage_workflow", false, "加车失败",
                contextWith(existing)));

        assertThat(result.chainToPersist().status()).isEqualTo("FAILED");
        assertThat(result.chainToPersist().steps()).hasSize(2);
        assertThat(result.chainToPersist().steps().get(1).status()).isEqualTo("FAILED");
    }

    @Test
    void productGroupSameGroupTransitionIsAllowed() {
        ConversationRuntimeContext.TaskChain existing = chainWithLastStep(
                "chain-A", "PRICE_QUERY", "SUCCEEDED", "EXECUTING");
        TaskChainPlanner.PlanResult result = planner.plan(input(
                "PRODUCT_DETAIL_QUERY", "product_query_workflow", true, "看详情",
                contextWith(existing)));

        assertThat(result.previousChainIdToCancel()).isNull();
        assertThat(result.chainToPersist().taskChainId()).isEqualTo("chain-A");
        assertThat(result.chainToPersist().steps()).hasSize(2);
    }

    private TaskChainPlanner.TurnInput input(
            String intent, String workflow, boolean success, String message,
            ConversationRuntimeContext context
    ) {
        return new TaskChainPlanner.TurnInput(
                context, "turn-" + System.nanoTime(), intent, workflow, success, message,
                new ConversationRuntimeContext.StepOutput(intent, Map.of())
        );
    }

    private ConversationRuntimeContext emptyContext() {
        return contextWith();
    }

    private ConversationRuntimeContext contextWith(ConversationRuntimeContext.TaskChain... chains) {
        return new ConversationRuntimeContext(
                1L, "u1", "c1", List.of(),
                null, List.of(), null, null, null, null,
                new HashMap<>(), new HashMap<>(),
                new ArrayList<>(List.of(chains))
        );
    }

    private ConversationRuntimeContext.TaskChain chainWithLastStep(
            String chainId, String lastStepIntent, String stepStatus, String chainStatus
    ) {
        ConversationRuntimeContext.TaskStep step = new ConversationRuntimeContext.TaskStep(
                1, "step-1", lastStepIntent, "previous request", "wf",
                stepStatus, "turn-prev", LocalDateTime.now().minusMinutes(5),
                LocalDateTime.now().minusMinutes(5),
                new ConversationRuntimeContext.StepOutput(lastStepIntent, Map.of())
        );
        return new ConversationRuntimeContext.TaskChain(
                100L, chainId, 1, chainStatus,
                new ConversationRuntimeContext.UserGoal(lastStepIntent, "previous", false, chainStatus),
                List.of(step),
                "turn-prev", "turn-prev",
                LocalDateTime.now().minusMinutes(10),
                LocalDateTime.now().minusMinutes(5)
        );
    }

    private static ConversationContextManager noOpManager() {
        return new ConversationContextManager() {
            @Override public ConversationRuntimeContext load(String u, String c) { return null; }
            @Override public void saveProductCandidates(String a, String b, String c, String d,
                    List<ConversationRuntimeContext.ProductCandidateItem> e, LocalDateTime f) {}
            @Override public void updateFocus(String a, String b, String c, String d,
                    ConversationRuntimeContext.Focus e, LocalDateTime f) {}
            @Override public ConversationRuntimeContext.PendingClarification savePendingClarification(
                    String a, String b, String c, String d,
                    ConversationRuntimeContext.PendingClarification e, LocalDateTime f) { return e; }
            @Override public void consumePendingClarification(Long id) {}
            @Override public void updateCartSnapshot(String a, String b, String c, String d,
                    ConversationRuntimeContext.CartSnapshot e, LocalDateTime f) {}
            @Override public ConversationRuntimeContext.OrderContext updateOrderContext(
                    String a, String b, String c, String d,
                    ConversationRuntimeContext.OrderContext e, LocalDateTime f) { return e; }
            @Override public void updateLastTurn(String a, String b, String c, String d,
                    ConversationRuntimeContext.LastTurn e, LocalDateTime f) {}
            @Override public boolean transitionOrderContextStatus(Long id, String s,
                    ConversationRuntimeContext.OrderContext o, ConversationContextItemStatus st) { return false; }
            @Override public void markContextItemStatus(Long id, ConversationContextItemStatus s) {}
            @Override public void saveTaskChain(String a, String b, String c, String d,
                    ConversationRuntimeContext.TaskChain e, LocalDateTime f) {}
            @Override public ConversationRuntimeContext.TaskChain loadTaskChain(String a, String b, String c) { return null; }
            @Override public boolean markChainStep(String a, String b, String c, int n, String s,
                    ConversationRuntimeContext.StepOutput o, String t) { return false; }
            @Override public boolean transitionChainStatus(String a, String b, String c, String s, String t) { return false; }
        };
    }
}
