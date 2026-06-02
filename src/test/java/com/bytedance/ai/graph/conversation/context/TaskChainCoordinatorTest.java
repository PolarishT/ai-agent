package com.bytedance.ai.graph.conversation.context;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TaskChainCoordinatorTest {

    @Test
    void startChainAssignsIdSetsStatusAndPersists() {
        RecordingContextManager mgr = new RecordingContextManager();
        TaskChainCoordinator coordinator = new TaskChainCoordinator(mgr);
        ConversationRuntimeContext.UserGoal goal = new ConversationRuntimeContext.UserGoal(
                "PRODUCT_DISCOVERY", "找防晒霜", false, "RUNNING"
        );
        ConversationRuntimeContext.TaskStep step = new ConversationRuntimeContext.TaskStep(
                1, "step-1", "PRODUCT_SEARCH", "搜",
                "product_query_workflow", "PENDING", null, null, null, null
        );

        ConversationRuntimeContext.TaskChain chain = coordinator.startChain(
                "u1", "c1", "turn-1", "main_intent_router",
                goal, List.of(step), TaskChainCoordinator.CHAIN_STATUS_EXECUTING
        );

        assertThat(chain.taskChainId()).startsWith("chain_");
        assertThat(chain.status()).isEqualTo("EXECUTING");
        assertThat(chain.userGoal()).isSameAs(goal);
        assertThat(chain.steps()).hasSize(1);
        assertThat(chain.createdTurnId()).isEqualTo("turn-1");
        assertThat(chain.lastUpdatedTurnId()).isEqualTo("turn-1");
        assertThat(mgr.savedChain).isSameAs(chain);
        assertThat(mgr.savedSourceWorkflow).isEqualTo("main_intent_router");
    }

    @Test
    void startChainDefaultsStatusToExecutingWhenNull() {
        RecordingContextManager mgr = new RecordingContextManager();
        TaskChainCoordinator coordinator = new TaskChainCoordinator(mgr);

        ConversationRuntimeContext.TaskChain chain = coordinator.startChain(
                "u1", "c1", "turn-1", null, null, null, null
        );

        assertThat(chain.status()).isEqualTo("EXECUTING");
        assertThat(chain.steps()).isEmpty();
    }

    @Test
    void findActiveChainPicksLatestPlanningOrExecutingByCreatedAt() {
        LocalDateTime now = LocalDateTime.now();
        ConversationRuntimeContext.TaskChain done = chainOf("chain-A", "SUCCEEDED", now.minusHours(2));
        ConversationRuntimeContext.TaskChain planning = chainOf("chain-B", "PLANNING", now.minusHours(1));
        ConversationRuntimeContext.TaskChain executing = chainOf("chain-C", "EXECUTING", now);
        RecordingContextManager mgr = new RecordingContextManager();
        mgr.taskChainsForLoad = List.of(done, planning, executing);
        TaskChainCoordinator coordinator = new TaskChainCoordinator(mgr);

        Optional<ConversationRuntimeContext.TaskChain> active = coordinator.findActiveChain("u1", "c1");

        assertThat(active).isPresent();
        assertThat(active.get().taskChainId()).isEqualTo("chain-C");
    }

    @Test
    void findActiveChainReturnsEmptyWhenAllTerminal() {
        LocalDateTime now = LocalDateTime.now();
        RecordingContextManager mgr = new RecordingContextManager();
        mgr.taskChainsForLoad = List.of(
                chainOf("a", "SUCCEEDED", now.minusHours(1)),
                chainOf("b", "FAILED", now)
        );
        TaskChainCoordinator coordinator = new TaskChainCoordinator(mgr);

        assertThat(coordinator.findActiveChain("u1", "c1")).isEmpty();
    }

    @Test
    void nextPendingStepReturnsLowestStepNoAmongPending() {
        ConversationRuntimeContext.TaskStep s1 = step(1, "SUCCEEDED");
        ConversationRuntimeContext.TaskStep s2 = step(2, "PENDING");
        ConversationRuntimeContext.TaskStep s3 = step(3, "PENDING");
        ConversationRuntimeContext.TaskChain chain = new ConversationRuntimeContext.TaskChain(
                null, "chain-1", 1, "EXECUTING", null,
                List.of(s3, s1, s2), null, null, null, null
        );
        TaskChainCoordinator coordinator = new TaskChainCoordinator(new RecordingContextManager());

        Optional<ConversationRuntimeContext.TaskStep> next = coordinator.nextPendingStep(chain);

        assertThat(next).isPresent();
        assertThat(next.get().stepNo()).isEqualTo(2);
    }

    @Test
    void nextPendingStepReturnsEmptyWhenChainNullOrNoPending() {
        TaskChainCoordinator coordinator = new TaskChainCoordinator(new RecordingContextManager());
        assertThat(coordinator.nextPendingStep(null)).isEmpty();

        ConversationRuntimeContext.TaskChain doneChain = new ConversationRuntimeContext.TaskChain(
                null, "c", 1, "SUCCEEDED", null,
                List.of(step(1, "SUCCEEDED")), null, null, null, null
        );
        assertThat(coordinator.nextPendingStep(doneChain)).isEmpty();
    }

    @Test
    void stepLifecycleHelpersDelegateToManagerWithExpectedStatus() {
        RecordingContextManager mgr = new RecordingContextManager();
        TaskChainCoordinator coordinator = new TaskChainCoordinator(mgr);
        ConversationRuntimeContext.StepOutput output = new ConversationRuntimeContext.StepOutput(
                "PRODUCT_CANDIDATES", Map.of()
        );

        coordinator.startStep("u", "c", "ch", 1, "t-1");
        assertThat(mgr.lastMarkCall).isEqualTo(new MarkCall("ch", 1, "RUNNING", null, "t-1"));

        coordinator.completeStep("u", "c", "ch", 2, output, "t-2");
        assertThat(mgr.lastMarkCall).isEqualTo(new MarkCall("ch", 2, "SUCCEEDED", output, "t-2"));

        coordinator.failStep("u", "c", "ch", 3, output, "t-3");
        assertThat(mgr.lastMarkCall).isEqualTo(new MarkCall("ch", 3, "FAILED", output, "t-3"));

        coordinator.completeChain("u", "c", "ch", "SUCCEEDED", "t-4");
        assertThat(mgr.lastTransitionCall).isEqualTo(new TransitionCall("ch", "SUCCEEDED", "t-4"));
    }

    @Test
    void newChainIdAndNewStepIdReturnUniquePrefixedIds() {
        TaskChainCoordinator coordinator = new TaskChainCoordinator(new RecordingContextManager());
        String c1 = coordinator.newChainId();
        String c2 = coordinator.newChainId();
        String s1 = coordinator.newStepId();
        String s2 = coordinator.newStepId();

        assertThat(c1).startsWith("chain_");
        assertThat(c2).startsWith("chain_");
        assertThat(s1).startsWith("step_");
        assertThat(s2).startsWith("step_");
        assertThat(c1).isNotEqualTo(c2);
        assertThat(s1).isNotEqualTo(s2);
    }

    private static ConversationRuntimeContext.TaskChain chainOf(String id, String status, LocalDateTime createdAt) {
        return new ConversationRuntimeContext.TaskChain(
                null, id, 1, status, null, List.of(), null, null, createdAt, createdAt
        );
    }

    private static ConversationRuntimeContext.TaskStep step(int stepNo, String status) {
        return new ConversationRuntimeContext.TaskStep(
                stepNo, "step-" + stepNo, "X", "x", "wf",
                status, null, null, null, null
        );
    }

    private record MarkCall(
            String chainId,
            int stepNo,
            String status,
            ConversationRuntimeContext.StepOutput output,
            String turnId
    ) {
    }

    private record TransitionCall(String chainId, String status, String turnId) {
    }

    private static final class RecordingContextManager implements ConversationContextManager {
        ConversationRuntimeContext.TaskChain savedChain;
        String savedSourceWorkflow;
        MarkCall lastMarkCall;
        TransitionCall lastTransitionCall;
        List<ConversationRuntimeContext.TaskChain> taskChainsForLoad = List.of();

        @Override
        public ConversationRuntimeContext load(String userId, String conversationId) {
            return new ConversationRuntimeContext(
                    1L, userId, conversationId, List.of(), null, List.of(), null, null, null, null,
                    new HashMap<>(), new HashMap<>(), new ArrayList<>(taskChainsForLoad)
            );
        }

        @Override
        public void saveProductCandidates(String userId, String conversationId, String sourceTurnId,
                                          String sourceWorkflow,
                                          List<ConversationRuntimeContext.ProductCandidateItem> candidates,
                                          LocalDateTime expiresAt) {
        }

        @Override
        public void updateFocus(String userId, String conversationId, String sourceTurnId, String sourceWorkflow,
                                ConversationRuntimeContext.Focus focus, LocalDateTime expiresAt) {
        }

        @Override
        public ConversationRuntimeContext.PendingClarification savePendingClarification(
                String userId, String conversationId, String sourceTurnId, String sourceWorkflow,
                ConversationRuntimeContext.PendingClarification clarification, LocalDateTime expiresAt) {
            return clarification;
        }

        @Override
        public void consumePendingClarification(Long contextItemId) {
        }

        @Override
        public void updateCartSnapshot(String userId, String conversationId, String sourceTurnId,
                                       String sourceWorkflow,
                                       ConversationRuntimeContext.CartSnapshot cartSnapshot,
                                       LocalDateTime expiresAt) {
        }

        @Override
        public ConversationRuntimeContext.OrderContext updateOrderContext(
                String userId, String conversationId, String sourceTurnId, String sourceWorkflow,
                ConversationRuntimeContext.OrderContext orderContext, LocalDateTime expiresAt) {
            return orderContext;
        }

        @Override
        public void updateLastTurn(String userId, String conversationId, String sourceTurnId,
                                   String sourceWorkflow,
                                   ConversationRuntimeContext.LastTurn lastTurn, LocalDateTime expiresAt) {
        }

        @Override
        public boolean transitionOrderContextStatus(Long contextItemId, String expectedOrderStatus,
                                                    ConversationRuntimeContext.OrderContext orderContext,
                                                    ConversationContextItemStatus itemStatus) {
            return false;
        }

        @Override
        public void markContextItemStatus(Long contextItemId, ConversationContextItemStatus status) {
        }

        @Override
        public void saveTaskChain(String userId, String conversationId, String sourceTurnId,
                                  String sourceWorkflow,
                                  ConversationRuntimeContext.TaskChain taskChain, LocalDateTime expiresAt) {
            this.savedChain = taskChain;
            this.savedSourceWorkflow = sourceWorkflow;
        }

        @Override
        public ConversationRuntimeContext.TaskChain loadTaskChain(String userId, String conversationId,
                                                                  String taskChainId) {
            return taskChainsForLoad.stream()
                    .filter(c -> taskChainId.equals(c.taskChainId()))
                    .findFirst().orElse(null);
        }

        @Override
        public boolean markChainStep(String userId, String conversationId, String taskChainId, int stepNo,
                                     String newStepStatus, ConversationRuntimeContext.StepOutput output,
                                     String turnId) {
            this.lastMarkCall = new MarkCall(taskChainId, stepNo, newStepStatus, output, turnId);
            return true;
        }

        @Override
        public boolean transitionChainStatus(String userId, String conversationId, String taskChainId,
                                             String newChainStatus, String turnId) {
            this.lastTransitionCall = new TransitionCall(taskChainId, newChainStatus, turnId);
            return true;
        }
    }
}
