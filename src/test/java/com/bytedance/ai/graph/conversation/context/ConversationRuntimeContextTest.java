package com.bytedance.ai.graph.conversation.context;

import com.bytedance.ai.graph.conversation.ConversationMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationRuntimeContextTest {

    @Test
    void activeChainReturnsNullWhenNoneActive() {
        ConversationRuntimeContext ctx = ConversationRuntimeContext.empty(1L, "u1", "c1", List.of());
        assertThat(ctx.activeChain()).isNull();
    }

    @Test
    void activeChainPicksLatestExecutingOrPlanning() {
        LocalDateTime now = LocalDateTime.now();
        ConversationRuntimeContext.TaskChain done = chain("done", "SUCCEEDED", now.minusHours(2));
        ConversationRuntimeContext.TaskChain planning = chain("plan", "PLANNING", now.minusHours(1));
        ConversationRuntimeContext.TaskChain executing = chain("exec", "EXECUTING", now);
        ConversationRuntimeContext ctx = withChains(done, planning, executing);

        assertThat(ctx.activeChain()).isNotNull();
        assertThat(ctx.activeChain().taskChainId()).isEqualTo("exec");
    }

    @Test
    void nextPendingTaskReturnsLowestOrderPending() {
        ConversationRuntimeContext.PlanTask t1 = task("a", 1, "SUCCEEDED");
        ConversationRuntimeContext.PlanTask t2 = task("b", 2, "PENDING");
        ConversationRuntimeContext.PlanTask t3 = task("c", 3, "PENDING");
        ConversationRuntimeContext.TaskChain chain = new ConversationRuntimeContext.TaskChain(
                null, "ch", 1, "EXECUTING", null, List.of(t3, t1, t2), List.of(),
                null, null, LocalDateTime.now(), LocalDateTime.now());

        assertThat(chain.nextPendingTask()).isNotNull();
        assertThat(chain.nextPendingTask().order()).isEqualTo(2);
    }

    @Test
    void viewSplitsTaskChainAndTaskSummaries() {
        ConversationMessage msg = new ConversationMessage(
                1L, "turn-1:user:x", 1L, "USER", "买防晒霜", "SUCCEEDED", "corr", 1, OffsetDateTime.now());
        ConversationRuntimeContext.PlanTask t1 = task("t1", 1, "SUCCEEDED");
        ConversationRuntimeContext.PlanTask t2 = task("t2", 2, "PENDING");
        ConversationRuntimeContext.TaskStep step = new ConversationRuntimeContext.TaskStep(
                1, "step-1", "PRODUCT_SEARCH", "搜", "product_query_workflow", "SUCCEEDED",
                "turn-1", LocalDateTime.now(), LocalDateTime.now(), Map.of("candidateCount", 3));
        ConversationRuntimeContext.TaskChain chain = new ConversationRuntimeContext.TaskChain(
                10L, "chain-1", 1, "EXECUTING",
                new ConversationRuntimeContext.UserGoal("PRODUCT_DISCOVERY", "买防晒霜", true, "RUNNING"),
                List.of(t1, t2), List.of(step), "turn-1", "turn-1",
                LocalDateTime.now(), LocalDateTime.now());
        ConversationRuntimeContext ctx = new ConversationRuntimeContext(
                1L, "u1", "c1", List.of(msg), null, List.of(), null, null, null, null,
                Map.of(), Map.of(), List.of(chain));

        RuntimeContextView view = RuntimeContextView.from(ctx, "turn-1", "req-1");

        assertThat(view.conversationId()).isEqualTo("c1");
        assertThat(view.currentTurnId()).isEqualTo("turn-1");
        assertThat(view.requestId()).isEqualTo("req-1");
        // recentMessages：turnId 从 messageId 拆出
        assertThat(view.recentMessages()).hasSize(1);
        assertThat(view.recentMessages().get(0).turnId()).isEqualTo("turn-1");
        assertThat(view.recentMessages().get(0).content()).isEqualTo("买防晒霜");
        // taskChain = 活跃链的计划清单（轻量 {taskId,taskName,status}）
        assertThat(view.taskChain()).hasSize(2);
        assertThat(view.taskChain().get(0).status()).isEqualTo("SUCCEEDED");
        assertThat(view.taskChain().get(1).status()).isEqualTo("PENDING");
        // taskSummaries = 明细（含 steps + output）
        assertThat(view.taskSummaries()).hasSize(1);
        assertThat(view.taskSummaries().get(0).taskChainId()).isEqualTo("chain-1");
        assertThat(view.taskSummaries().get(0).userGoal().goalType()).isEqualTo("PRODUCT_DISCOVERY");
        assertThat(view.taskSummaries().get(0).steps()).hasSize(1);
        assertThat(view.taskSummaries().get(0).steps().get(0).output()).containsEntry("candidateCount", 3);
    }

    @Test
    void viewTaskChainEmptyWhenNoActiveChain() {
        ConversationRuntimeContext.TaskChain done = chain("done", "SUCCEEDED", LocalDateTime.now());
        ConversationRuntimeContext ctx = withChains(done);

        RuntimeContextView view = RuntimeContextView.from(ctx, "turn-1", "req-1");

        assertThat(view.taskChain()).isEmpty();
        assertThat(view.taskSummaries()).hasSize(1);
    }

    private static ConversationRuntimeContext withChains(ConversationRuntimeContext.TaskChain... chains) {
        return new ConversationRuntimeContext(
                1L, "u1", "c1", List.of(), null, List.of(), null, null, null, null,
                Map.of(), Map.of(), List.of(chains));
    }

    private static ConversationRuntimeContext.TaskChain chain(String id, String status, LocalDateTime createdAt) {
        return new ConversationRuntimeContext.TaskChain(
                null, id, 1, status, null, List.of(), List.of(), null, null, createdAt, createdAt);
    }

    private static ConversationRuntimeContext.PlanTask task(String id, int order, String status) {
        return new ConversationRuntimeContext.PlanTask(id, id + " 任务", "PRODUCT_SEARCH",
                "product_query_workflow", order, status);
    }
}
