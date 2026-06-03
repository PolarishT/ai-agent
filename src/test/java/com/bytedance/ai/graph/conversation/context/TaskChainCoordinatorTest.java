package com.bytedance.ai.graph.conversation.context;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TaskChainCoordinatorTest {

    private final FakeManager manager = new FakeManager();
    private final TaskChainCoordinator coordinator = new TaskChainCoordinator(manager);

    @Test
    void startChainPersistsWithPlanTasksAndStatus() {
        ConversationRuntimeContext.UserGoal goal =
                new ConversationRuntimeContext.UserGoal("PRODUCT_SEARCH", "找防晒霜", false, "RUNNING");
        ConversationRuntimeContext.PlanTask t = planTask("PRODUCT_SEARCH", "product_query_workflow", 1);

        ConversationRuntimeContext.TaskChain chain = coordinator.startChain(
                "u1", "c1", "turn-1", "product_query_workflow", goal, List.of(t), "EXECUTING");

        assertThat(chain.taskChainId()).startsWith("chain_");
        assertThat(chain.status()).isEqualTo("EXECUTING");
        assertThat(chain.planTasks()).hasSize(1);
        ConversationRuntimeContext.TaskChain stored = manager.loadTaskChain("u1", "c1", chain.taskChainId());
        assertThat(stored).isNotNull();
        assertThat(stored.planTasks().get(0).workflow()).isEqualTo("product_query_workflow");
    }

    @Test
    void populatePlanFillsTasksAndFlipsToExecuting() {
        ConversationRuntimeContext.TaskChain chain = coordinator.startChain(
                "u1", "c1", "turn-1", "wf", goal("PRODUCT_DISCOVERY"), List.of(), "PLANNING");

        coordinator.populatePlan("u1", "c1", chain.taskChainId(), List.of(
                planTask("PRODUCT_SEARCH", "product_query_workflow", 1),
                planTask("ADD_TO_CART", "cart_manage_workflow", 2)
        ), "turn-1");

        ConversationRuntimeContext.TaskChain stored = manager.loadTaskChain("u1", "c1", chain.taskChainId());
        assertThat(stored.status()).isEqualTo("EXECUTING");
        assertThat(stored.planTasks()).hasSize(2);
    }

    @Test
    void findActiveChainReturnsExecutingOrPlanning() {
        coordinator.startChain("u1", "c1", "t", "wf", goal("X"), List.of(), "PLANNING");
        Optional<ConversationRuntimeContext.TaskChain> active = coordinator.findActiveChain("u1", "c1");
        assertThat(active).isPresent();
        assertThat(active.get().status()).isEqualTo("PLANNING");
    }

    @Test
    void nextPendingTaskReturnsLowestOrderPending() {
        ConversationRuntimeContext.PlanTask t1 = withStatus(planTask("A", "wf", 1), "SUCCEEDED");
        ConversationRuntimeContext.PlanTask t2 = planTask("B", "wf", 2);
        ConversationRuntimeContext.PlanTask t3 = planTask("C", "wf", 3);
        ConversationRuntimeContext.TaskChain chain = new ConversationRuntimeContext.TaskChain(
                null, "ch", 1, "EXECUTING", null, List.of(t3, t1, t2), List.of(),
                null, null, LocalDateTime.now(), LocalDateTime.now());

        Optional<ConversationRuntimeContext.PlanTask> next = coordinator.nextPendingTask(chain);
        assertThat(next).isPresent();
        assertThat(next.get().order()).isEqualTo(2);
    }

    @Test
    void completeTaskFlipsStatusAndAppendsStep() {
        ConversationRuntimeContext.PlanTask t = planTask("PRODUCT_SEARCH", "product_query_workflow", 1);
        ConversationRuntimeContext.TaskChain chain = coordinator.startChain(
                "u1", "c1", "turn-1", "wf", goal("X"), List.of(t), "EXECUTING");
        ConversationRuntimeContext.TaskStep step = coordinator.buildStep(
                1, t, "SUCCEEDED", Map.of("candidateCount", 3), "turn-1",
                LocalDateTime.now(), LocalDateTime.now());

        boolean ok = coordinator.completeTask("u1", "c1", chain.taskChainId(), t.taskId(), step, "turn-1");

        assertThat(ok).isTrue();
        ConversationRuntimeContext.TaskChain stored = manager.loadTaskChain("u1", "c1", chain.taskChainId());
        assertThat(stored.planTasks().get(0).status()).isEqualTo("SUCCEEDED");
        assertThat(stored.steps()).hasSize(1);
        assertThat(stored.steps().get(0).output()).containsEntry("candidateCount", 3);
        assertThat(stored.nextPendingTask()).isNull();
    }

    @Test
    void transitionChainUpdatesStatus() {
        ConversationRuntimeContext.TaskChain chain = coordinator.startChain(
                "u1", "c1", "turn-1", "wf", goal("X"),
                List.of(planTask("A", "wf", 1)), "EXECUTING");

        assertThat(coordinator.transitionChain("u1", "c1", chain.taskChainId(), "SUCCEEDED", "turn-2")).isTrue();
        assertThat(manager.loadTaskChain("u1", "c1", chain.taskChainId()).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void idGeneratorsAreUniqueAndPrefixed() {
        assertThat(coordinator.newChainId()).startsWith("chain_");
        assertThat(coordinator.newTaskId()).startsWith("task_");
        assertThat(coordinator.newStepId()).startsWith("step_");
        assertThat(coordinator.newChainId()).isNotEqualTo(coordinator.newChainId());
    }

    private ConversationRuntimeContext.UserGoal goal(String type) {
        return new ConversationRuntimeContext.UserGoal(type, "goal", false, "RUNNING");
    }

    private ConversationRuntimeContext.PlanTask planTask(String taskType, String workflow, int order) {
        return new ConversationRuntimeContext.PlanTask(
                "task-" + order, taskType + " 任务", taskType, workflow, order, "PENDING");
    }

    private ConversationRuntimeContext.PlanTask withStatus(ConversationRuntimeContext.PlanTask t, String status) {
        return new ConversationRuntimeContext.PlanTask(
                t.taskId(), t.taskName(), t.taskType(), t.workflow(), t.order(), status);
    }

    /** 真存的内存版 manager：只实现链相关方法，其余 no-op。 */
    private static final class FakeManager implements ConversationContextManager {
        private final Map<String, ConversationRuntimeContext.TaskChain> chains = new LinkedHashMap<>();

        @Override
        public ConversationRuntimeContext load(String userId, String conversationId) {
            return new ConversationRuntimeContext(
                    1L, userId, conversationId, List.of(), null, List.of(), null, null, null, null,
                    Map.of(), Map.of(), new ArrayList<>(chains.values()));
        }

        @Override
        public void saveTaskChain(String userId, String conversationId, String sourceTurnId,
                                  String sourceWorkflow, ConversationRuntimeContext.TaskChain taskChain,
                                  LocalDateTime expiresAt) {
            chains.put(taskChain.taskChainId(), taskChain);
        }

        @Override
        public ConversationRuntimeContext.TaskChain loadTaskChain(String userId, String conversationId,
                                                                  String taskChainId) {
            return chains.get(taskChainId);
        }

        @Override
        public boolean markPlanTask(String userId, String conversationId, String taskChainId, String taskId,
                                    String newTaskStatus, ConversationRuntimeContext.TaskStep executedStep,
                                    String turnId) {
            ConversationRuntimeContext.TaskChain chain = chains.get(taskChainId);
            if (chain == null) {
                return false;
            }
            List<ConversationRuntimeContext.PlanTask> plan = new ArrayList<>();
            boolean found = false;
            for (ConversationRuntimeContext.PlanTask t : chain.planTasks()) {
                if (t.taskId().equals(taskId)) {
                    plan.add(new ConversationRuntimeContext.PlanTask(
                            t.taskId(), t.taskName(), t.taskType(), t.workflow(), t.order(), newTaskStatus));
                    found = true;
                } else {
                    plan.add(t);
                }
            }
            if (!found) {
                return false;
            }
            List<ConversationRuntimeContext.TaskStep> steps = new ArrayList<>(chain.steps());
            if (executedStep != null) {
                steps.add(executedStep);
            }
            chains.put(taskChainId, new ConversationRuntimeContext.TaskChain(
                    null, chain.taskChainId(), chain.schemaVersion(), chain.status(), chain.userGoal(),
                    plan, steps, chain.createdTurnId(), turnId, chain.createdAt(), LocalDateTime.now()));
            return true;
        }

        @Override
        public boolean transitionChainStatus(String userId, String conversationId, String taskChainId,
                                             String newChainStatus, String turnId) {
            ConversationRuntimeContext.TaskChain chain = chains.get(taskChainId);
            if (chain == null) {
                return false;
            }
            chains.put(taskChainId, new ConversationRuntimeContext.TaskChain(
                    null, chain.taskChainId(), chain.schemaVersion(), newChainStatus, chain.userGoal(),
                    chain.planTasks(), chain.steps(), chain.createdTurnId(), turnId,
                    chain.createdAt(), LocalDateTime.now()));
            return true;
        }

        // --- 其余接口方法 no-op ---
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
    }
}
