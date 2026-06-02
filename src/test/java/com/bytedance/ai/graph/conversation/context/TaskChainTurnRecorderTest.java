package com.bytedance.ai.graph.conversation.context;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskChainTurnRecorderTest {

    @Test
    void recordTurnDelegatesToMapperPlannerAndPersistsDecision() {
        ConversationRuntimeContext.TaskChain plannerChain = new ConversationRuntimeContext.TaskChain(
                null, "chain-A", 1, "EXECUTING",
                new ConversationRuntimeContext.UserGoal("PRODUCT_SEARCH", "找防晒霜", false, "EXECUTING"),
                List.of(), null, "turn-1", LocalDateTime.now(), LocalDateTime.now()
        );
        StubPlanner planner = new StubPlanner(new TaskChainPlanner.PlanResult(plannerChain, null));
        CapturingContextManager mgr = new CapturingContextManager();
        RecordingMapper mapper = new RecordingMapper();
        TaskChainTurnRecorder recorder = new TaskChainTurnRecorder(mgr, planner, mapper, 60);

        recorder.recordTurn(
                runtimeContextWith(),
                "u1", "c1", "turn-1",
                "PRODUCT_SEARCH", "product_query_workflow",
                true, "找防晒霜", Map.of("count", 3), "给你推荐 3 款"
        );

        // mapper 拿到 (intent, workflowResult, answerText)
        assertThat(mapper.lastIntent).isEqualTo("PRODUCT_SEARCH");
        assertThat(mapper.lastWorkflowResult).isEqualTo(Map.of("count", 3));
        assertThat(mapper.lastAnswerText).isEqualTo("给你推荐 3 款");

        // planner 接到 mapper 的 output
        assertThat(planner.lastInput).isNotNull();
        assertThat(planner.lastInput.intent()).isEqualTo("PRODUCT_SEARCH");
        assertThat(planner.lastInput.workflow()).isEqualTo("product_query_workflow");
        assertThat(planner.lastInput.success()).isTrue();
        assertThat(planner.lastInput.userMessage()).isEqualTo("找防晒霜");
        assertThat(planner.lastInput.output()).isSameAs(mapper.returnedOutput);

        // recorder saved planner's chain, no cancellation issued
        assertThat(mgr.savedChain).isSameAs(plannerChain);
        assertThat(mgr.cancelledChainId).isNull();
    }

    @Test
    void cancelsPreviousChainBeforeSavingNewWhenPlannerSaysSo() {
        ConversationRuntimeContext.TaskChain freshChain = new ConversationRuntimeContext.TaskChain(
                null, "chain-B", 1, "EXECUTING", null, List.of(),
                null, null, LocalDateTime.now(), LocalDateTime.now()
        );
        StubPlanner planner = new StubPlanner(new TaskChainPlanner.PlanResult(freshChain, "chain-A"));
        CapturingContextManager mgr = new CapturingContextManager();
        TaskChainTurnRecorder recorder = new TaskChainTurnRecorder(mgr, planner, recordingMapper(), 60);

        recorder.recordTurn(
                runtimeContextWith(),
                "u1", "c1", "turn-2",
                "CREATE_ORDER", "order_manage_workflow",
                true, null, null, "下单成功"
        );

        assertThat(mgr.cancelledChainId).isEqualTo("chain-A");
        assertThat(mgr.cancelledTurnId).isEqualTo("turn-2");
        assertThat(mgr.savedChain).isSameAs(freshChain);
    }

    @Test
    void skipsPersistenceWhenPlannerReturnsNullChain() {
        StubPlanner planner = new StubPlanner(new TaskChainPlanner.PlanResult(null, null));
        CapturingContextManager mgr = new CapturingContextManager();
        TaskChainTurnRecorder recorder = new TaskChainTurnRecorder(mgr, planner, recordingMapper(), 60);

        recorder.recordTurn(
                runtimeContextWith(),
                "u1", "c1", "turn-3",
                "CLARIFY", "clarify_workflow",
                true, "?", null, "你想问什么"
        );

        assertThat(mgr.savedChain).isNull();
        assertThat(mgr.cancelledChainId).isNull();
        // planner 仍然被询问过
        assertThat(planner.lastInput).isNotNull();
    }

    @Test
    void skipsBeforePlannerWhenIdentifiersBlank() {
        StubPlanner planner = new StubPlanner(new TaskChainPlanner.PlanResult(
                new ConversationRuntimeContext.TaskChain(null, "X", 1, "EXECUTING",
                        null, List.of(), null, null, LocalDateTime.now(), LocalDateTime.now()),
                null
        ));
        CapturingContextManager mgr = new CapturingContextManager();
        TaskChainTurnRecorder recorder = new TaskChainTurnRecorder(mgr, planner, recordingMapper(), 60);

        recorder.recordTurn(null, "", "c1", "t", "X", "wf", true, null, null, null);
        recorder.recordTurn(null, "u1", "", "t", "X", "wf", true, null, null, null);
        recorder.recordTurn(null, "u1", "c1", "", "X", "wf", true, null, null, null);

        assertThat(planner.lastInput).isNull();
        assertThat(mgr.savedChain).isNull();
        assertThat(mgr.cancelledChainId).isNull();
    }

    @Test
    void fallbackForBlankIntentAndWorkflow() {
        StubPlanner planner = new StubPlanner(new TaskChainPlanner.PlanResult(
                new ConversationRuntimeContext.TaskChain(null, "X", 1, "EXECUTING",
                        null, List.of(), null, null, LocalDateTime.now(), LocalDateTime.now()),
                null
        ));
        CapturingContextManager mgr = new CapturingContextManager();
        TaskChainTurnRecorder recorder = new TaskChainTurnRecorder(mgr, planner, recordingMapper(), 60);

        recorder.recordTurn(null, "u1", "c1", "turn-4", null, "", true, null, null, "ok");

        assertThat(planner.lastInput.intent()).isEqualTo("UNKNOWN");
        assertThat(planner.lastInput.workflow()).isEqualTo("unknown_workflow");
        assertThat(planner.lastInput.userMessage()).isNull();
    }

    @Test
    void executingChainGetsExpiresAtBasedOnIdleTimeout() {
        ConversationRuntimeContext.TaskChain executing = new ConversationRuntimeContext.TaskChain(
                null, "chain-EX", 1, "EXECUTING", null, List.of(),
                null, null, LocalDateTime.now(), LocalDateTime.now()
        );
        StubPlanner planner = new StubPlanner(new TaskChainPlanner.PlanResult(executing, null));
        CapturingContextManager mgr = new CapturingContextManager();
        TaskChainTurnRecorder recorder = new TaskChainTurnRecorder(mgr, planner, recordingMapper(), 45);

        LocalDateTime before = LocalDateTime.now();
        recorder.recordTurn(runtimeContextWith(), "u1", "c1", "turn-1",
                "PRODUCT_SEARCH", "product_query_workflow", true, "找", null, "ok");
        LocalDateTime after = LocalDateTime.now();

        assertThat(mgr.savedExpiresAt).isNotNull();
        // 应该落在 [before+45min, after+45min] 区间，允许少量执行偏差
        assertThat(mgr.savedExpiresAt).isAfterOrEqualTo(before.plusMinutes(45).minusSeconds(1));
        assertThat(mgr.savedExpiresAt).isBeforeOrEqualTo(after.plusMinutes(45).plusSeconds(1));
    }

    @Test
    void terminalChainStatusGetsNoExpiresAt() {
        for (String terminal : List.of("SUCCEEDED", "FAILED", "CANCELLED")) {
            ConversationRuntimeContext.TaskChain done = new ConversationRuntimeContext.TaskChain(
                    null, "chain-" + terminal, 1, terminal, null, List.of(),
                    null, null, LocalDateTime.now(), LocalDateTime.now()
            );
            StubPlanner planner = new StubPlanner(new TaskChainPlanner.PlanResult(done, null));
            CapturingContextManager mgr = new CapturingContextManager();
            TaskChainTurnRecorder recorder = new TaskChainTurnRecorder(mgr, planner, recordingMapper(), 30);

            recorder.recordTurn(runtimeContextWith(), "u1", "c1", "turn-x",
                    "PRODUCT_SEARCH", "wf", false, null, null, "ok");

            assertThat(mgr.savedExpiresAt).as("terminal status %s -> null", terminal).isNull();
        }
    }

    @Test
    void idleTimeoutZeroDisablesExpiresAt() {
        ConversationRuntimeContext.TaskChain executing = new ConversationRuntimeContext.TaskChain(
                null, "chain-EX", 1, "EXECUTING", null, List.of(),
                null, null, LocalDateTime.now(), LocalDateTime.now()
        );
        StubPlanner planner = new StubPlanner(new TaskChainPlanner.PlanResult(executing, null));
        CapturingContextManager mgr = new CapturingContextManager();
        TaskChainTurnRecorder recorder = new TaskChainTurnRecorder(mgr, planner, recordingMapper(), 0);

        recorder.recordTurn(runtimeContextWith(), "u1", "c1", "turn-1",
                "PRODUCT_SEARCH", "wf", true, "x", null, "ok");

        assertThat(mgr.savedExpiresAt).isNull();
    }

    private static ConversationRuntimeContext runtimeContextWith(
            ConversationRuntimeContext.TaskChain... chains
    ) {
        return new ConversationRuntimeContext(
                1L, "u1", "c1", List.of(),
                null, List.of(), null, null, null, null,
                new HashMap<>(), new HashMap<>(),
                new ArrayList<>(List.of(chains))
        );
    }

    private static RecordingMapper recordingMapper() {
        return new RecordingMapper();
    }

    private static final class RecordingMapper implements StepOutputMapper {
        String lastIntent;
        Object lastWorkflowResult;
        String lastAnswerText;
        ConversationRuntimeContext.StepOutput returnedOutput;

        @Override
        public ConversationRuntimeContext.StepOutput map(
                String intent, Object workflowResult, String answerText
        ) {
            this.lastIntent = intent;
            this.lastWorkflowResult = workflowResult;
            this.lastAnswerText = answerText;
            this.returnedOutput = new ConversationRuntimeContext.StepOutput(
                    "STUB_KIND",
                    answerText == null ? Map.of() : Map.of("answer", answerText)
            );
            return returnedOutput;
        }
    }

    private static final class StubPlanner implements TaskChainPlanner {
        private final PlanResult result;
        TurnInput lastInput;

        StubPlanner(PlanResult result) {
            this.result = result;
        }

        @Override
        public PlanResult plan(TurnInput input) {
            this.lastInput = input;
            return result;
        }
    }

    private static final class CapturingContextManager implements ConversationContextManager {
        ConversationRuntimeContext.TaskChain savedChain;
        LocalDateTime savedExpiresAt;
        String cancelledChainId;
        String cancelledTurnId;

        @Override
        public ConversationRuntimeContext load(String userId, String conversationId) {
            return new ConversationRuntimeContext(
                    1L, userId, conversationId, List.of(),
                    null, List.of(), null, null, null, null,
                    new HashMap<>(), new HashMap<>(), new ArrayList<>()
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
            this.savedExpiresAt = expiresAt;
        }

        @Override
        public ConversationRuntimeContext.TaskChain loadTaskChain(String userId, String conversationId,
                                                                  String taskChainId) {
            return null;
        }

        @Override
        public boolean markChainStep(String userId, String conversationId, String taskChainId, int stepNo,
                                     String newStepStatus, ConversationRuntimeContext.StepOutput output,
                                     String turnId) {
            return false;
        }

        @Override
        public boolean transitionChainStatus(String userId, String conversationId, String taskChainId,
                                             String newChainStatus, String turnId) {
            this.cancelledChainId = taskChainId;
            this.cancelledTurnId = turnId;
            return true;
        }
    }
}
