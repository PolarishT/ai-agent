package com.bytedance.ai.graph.conversation.context;

import com.bytedance.ai.graph.conversation.ConversationMessage;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 喂给 LLM（planning / answer 节点）的运行时上下文序列化视图。
 *
 * <p>严格对齐约定的 JSON 结构，<b>只暴露</b>这些字段，不夹带内部 plumbing（focus/cart/order 等）：
 * <pre>
 * { conversationId, userId, currentTurnId, requestId,
 *   recentMessages[], taskChain[], taskSummaries[] }
 * </pre>
 *
 * <ul>
 *   <li>{@code taskChain} —— 当前活跃链的前向计划清单 {taskId, taskName, status}</li>
 *   <li>{@code taskSummaries} —— 所有链的后向明细日志（含 steps + output）</li>
 * </ul>
 */
public record RuntimeContextView(
        String conversationId,
        String userId,
        String currentTurnId,
        String requestId,
        List<MessageView> recentMessages,
        List<PlanTaskView> taskChain,
        List<TaskSummaryView> taskSummaries
) {

    public RuntimeContextView {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        taskChain = taskChain == null ? List.of() : List.copyOf(taskChain);
        taskSummaries = taskSummaries == null ? List.of() : List.copyOf(taskSummaries);
    }

    public static RuntimeContextView from(
            ConversationRuntimeContext context,
            String currentTurnId,
            String requestId
    ) {
        if (context == null) {
            return new RuntimeContextView(null, null, currentTurnId, requestId,
                    List.of(), List.of(), List.of());
        }

        List<MessageView> messages = new ArrayList<>();
        for (ConversationMessage m : context.recentMessages()) {
            messages.add(new MessageView(turnIdOf(m), m.role(), m.content(), m.createdAt()));
        }

        // taskChain = 当前活跃链的计划清单（无活跃链则空）
        List<PlanTaskView> plan = new ArrayList<>();
        ConversationRuntimeContext.TaskChain active = context.activeChain();
        if (active != null) {
            for (ConversationRuntimeContext.PlanTask t : active.planTasks()) {
                plan.add(new PlanTaskView(t.taskId(), t.taskName(), t.status()));
            }
        }

        // taskSummaries = 所有链的明细
        List<TaskSummaryView> summaries = new ArrayList<>();
        for (ConversationRuntimeContext.TaskChain chain : context.taskChains()) {
            summaries.add(toSummary(chain));
        }

        return new RuntimeContextView(
                context.conversationId(),
                context.userId(),
                currentTurnId,
                requestId,
                messages,
                plan,
                summaries
        );
    }

    public Map<String, Object> toStateMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("conversationId", conversationId);
        map.put("userId", userId);
        map.put("currentTurnId", currentTurnId);
        map.put("requestId", requestId);
        map.put("recentMessages", recentMessagesForState());
        map.put("taskChain", taskChainForState());
        map.put("taskSummaries", taskSummariesForState());
        return map;
    }

    public List<Map<String, Object>> recentMessagesForState() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MessageView message : recentMessages) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("turnId", message.turnId());
            map.put("role", message.role());
            map.put("content", message.content());
            map.put("createdAt", message.createdAt() == null ? null : message.createdAt().toString());
            result.add(map);
        }
        return result;
    }

    private List<Map<String, Object>> taskChainForState() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PlanTaskView task : taskChain) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("taskId", task.taskId());
            map.put("taskName", task.taskName());
            map.put("status", task.status());
            result.add(map);
        }
        return result;
    }

    private List<Map<String, Object>> taskSummariesForState() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TaskSummaryView summary : taskSummaries) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("taskChainId", summary.taskChainId());
            map.put("schemaVersion", summary.schemaVersion());
            map.put("userGoal", userGoalForState(summary.userGoal()));
            map.put("status", summary.status());
            map.put("steps", stepsForState(summary.steps()));
            map.put("createdTurnId", summary.createdTurnId());
            map.put("lastUpdatedTurnId", summary.lastUpdatedTurnId());
            map.put("createdAt", summary.createdAt() == null ? null : summary.createdAt().toString());
            map.put("updatedAt", summary.updatedAt() == null ? null : summary.updatedAt().toString());
            result.add(map);
        }
        return result;
    }

    private Map<String, Object> userGoalForState(UserGoalView goal) {
        if (goal == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("goalType", goal.goalType());
        map.put("goalText", goal.goalText());
        map.put("status", goal.status());
        return map;
    }

    private List<Map<String, Object>> stepsForState(List<StepView> steps) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StepView step : steps) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("stepNo", step.stepNo());
            map.put("stepId", step.stepId());
            map.put("taskType", step.taskType());
            map.put("taskText", step.taskText());
            map.put("workflow", step.workflow());
            map.put("status", step.status());
            map.put("output", step.output());
            map.put("turnId", step.turnId());
            map.put("startedAt", step.startedAt() == null ? null : step.startedAt().toString());
            map.put("completedAt", step.completedAt() == null ? null : step.completedAt().toString());
            result.add(map);
        }
        return result;
    }

    private static TaskSummaryView toSummary(ConversationRuntimeContext.TaskChain chain) {
        UserGoalView goal = chain.userGoal() == null ? null : new UserGoalView(
                chain.userGoal().goalType(),
                chain.userGoal().goalText(),
                chain.userGoal().status()
        );
        List<StepView> steps = new ArrayList<>();
        for (ConversationRuntimeContext.TaskStep s : chain.steps()) {
            steps.add(new StepView(
                    s.stepNo(), s.stepId(), s.taskType(), s.taskText(), s.workflow(),
                    s.status(), s.output(), s.turnId(), s.startedAt(), s.completedAt()
            ));
        }
        return new TaskSummaryView(
                chain.taskChainId(),
                chain.schemaVersion(),
                goal,
                chain.status(),
                steps,
                chain.createdTurnId(),
                chain.lastUpdatedTurnId(),
                chain.createdAt(),
                chain.updatedAt()
        );
    }

    /** message 没有单独的 turnId 列，按 messageId（格式 turnId:role:uuid）拆出来；拆不出回退 messageId。 */
    private static String turnIdOf(ConversationMessage m) {
        String messageId = m.messageId();
        if (messageId == null) {
            return null;
        }
        int idx = messageId.indexOf(':');
        return idx > 0 ? messageId.substring(0, idx) : messageId;
    }

    public record MessageView(
            String turnId,
            String role,
            String content,
            OffsetDateTime createdAt
    ) {
    }

    public record PlanTaskView(
            String taskId,
            String taskName,
            String status
    ) {
    }

    public record UserGoalView(
            String goalType,
            String goalText,
            String status
    ) {
    }

    public record TaskSummaryView(
            String taskChainId,
            int schemaVersion,
            UserGoalView userGoal,
            String status,
            List<StepView> steps,
            String createdTurnId,
            String lastUpdatedTurnId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record StepView(
            int stepNo,
            String stepId,
            String taskType,
            String taskText,
            String workflow,
            String status,
            Map<String, Object> output,
            String turnId,
            LocalDateTime startedAt,
            LocalDateTime completedAt
    ) {
    }
}
