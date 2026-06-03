package com.bytedance.ai.graph.conversation.context;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 任务链高层操作入口（前置状态机版）。封装：建链 / 取活跃链 / 取下一个 PENDING 任务 / 推进任务 / 转链状态。
 *
 * <p>典型流程：
 * <pre>
 *  // 规划阶段：复合目标 → 建 PLANNING 链
 *  TaskChain chain = coordinator.startChain(uid, cid, turnId, wf, goal, List.of(), PLANNING);
 *  // planning LLM 产出 planTasks 后填充并转 EXECUTING
 *  coordinator.populatePlan(uid, cid, chain.taskChainId(), planTasks, turnId);
 *
 *  // 执行阶段：取下一个 PENDING 任务，路由到它的 workflow，跑完推进
 *  PlanTask next = coordinator.nextPendingTask(chain).orElseThrow();
 *  coordinator.completeTask(uid, cid, chainId, next.taskId(), executedStep, turnId);
 *
 *  // 全部完成 → 收尾
 *  coordinator.transitionChain(uid, cid, chainId, SUCCEEDED, turnId);
 * </pre>
 */
@Service
public class TaskChainCoordinator {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static final String CHAIN_STATUS_PLANNING = "PLANNING";
    public static final String CHAIN_STATUS_EXECUTING = "EXECUTING";
    public static final String CHAIN_STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String CHAIN_STATUS_FAILED = "FAILED";
    public static final String CHAIN_STATUS_CANCELLED = "CANCELLED";

    public static final String TASK_STATUS_PENDING = "PENDING";
    public static final String TASK_STATUS_RUNNING = "RUNNING";
    public static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String TASK_STATUS_FAILED = "FAILED";
    public static final String TASK_STATUS_CANCELLED = "CANCELLED";

    private final ConversationContextManager contextManager;

    public TaskChainCoordinator(ConversationContextManager contextManager) {
        this.contextManager = contextManager;
    }

    /**
     * 新建并保存一条任务链。
     *
     * @param chainStatus 初始状态：PLANNING（等 planning LLM 拆任务）或 EXECUTING（已规划好直接执行）
     * @param planTasks   PLANNING 时通常为空；EXECUTING / 原子目标时至少含 1 个 PENDING 任务
     */
    public ConversationRuntimeContext.TaskChain startChain(
            String userId,
            String conversationId,
            String turnId,
            String sourceWorkflow,
            ConversationRuntimeContext.UserGoal userGoal,
            List<ConversationRuntimeContext.PlanTask> planTasks,
            String chainStatus
    ) {
        LocalDateTime now = LocalDateTime.now();
        ConversationRuntimeContext.TaskChain chain = new ConversationRuntimeContext.TaskChain(
                null,
                newChainId(),
                CURRENT_SCHEMA_VERSION,
                chainStatus == null ? CHAIN_STATUS_EXECUTING : chainStatus,
                userGoal,
                planTasks == null ? List.of() : planTasks,
                List.of(),
                turnId,
                turnId,
                now,
                now
        );
        contextManager.saveTaskChain(userId, conversationId, turnId, sourceWorkflow, chain, null);
        return chain;
    }

    /** PLANNING 链填入计划清单并转 EXECUTING。 */
    public void populatePlan(
            String userId,
            String conversationId,
            String taskChainId,
            List<ConversationRuntimeContext.PlanTask> planTasks,
            String turnId
    ) {
        ConversationRuntimeContext.TaskChain chain =
                contextManager.loadTaskChain(userId, conversationId, taskChainId);
        if (chain == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        ConversationRuntimeContext.TaskChain updated = new ConversationRuntimeContext.TaskChain(
                null,
                chain.taskChainId(),
                chain.schemaVersion(),
                CHAIN_STATUS_EXECUTING,
                chain.userGoal(),
                planTasks == null ? List.of() : planTasks,
                chain.steps(),
                chain.createdTurnId(),
                turnId,
                chain.createdAt() == null ? now : chain.createdAt(),
                now
        );
        contextManager.saveTaskChain(userId, conversationId, turnId, null, updated, null);
    }

    /** 找当前活跃任务链（PLANNING 或 EXECUTING），多个时取最近创建。 */
    public Optional<ConversationRuntimeContext.TaskChain> findActiveChain(
            String userId,
            String conversationId
    ) {
        ConversationRuntimeContext context = contextManager.load(userId, conversationId);
        return context.taskChains().stream()
                .filter(c -> CHAIN_STATUS_PLANNING.equals(c.status())
                        || CHAIN_STATUS_EXECUTING.equals(c.status()))
                .max(Comparator.comparing(
                        ConversationRuntimeContext.TaskChain::createdAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    /** 找 chain 内 order 最小的 PENDING 计划任务。 */
    public Optional<ConversationRuntimeContext.PlanTask> nextPendingTask(
            ConversationRuntimeContext.TaskChain chain
    ) {
        if (chain == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(chain.nextPendingTask());
    }

    /** 标计划任务 RUNNING（不追加 step）。 */
    public boolean startTask(
            String userId, String conversationId, String taskChainId, String taskId, String turnId
    ) {
        return contextManager.markPlanTask(userId, conversationId, taskChainId, taskId,
                TASK_STATUS_RUNNING, null, turnId);
    }

    /** 标计划任务 SUCCEEDED 并追加执行明细 step。 */
    public boolean completeTask(
            String userId, String conversationId, String taskChainId, String taskId,
            ConversationRuntimeContext.TaskStep executedStep, String turnId
    ) {
        return contextManager.markPlanTask(userId, conversationId, taskChainId, taskId,
                TASK_STATUS_SUCCEEDED, executedStep, turnId);
    }

    /** 标计划任务 FAILED 并追加执行明细 step。 */
    public boolean failTask(
            String userId, String conversationId, String taskChainId, String taskId,
            ConversationRuntimeContext.TaskStep executedStep, String turnId
    ) {
        return contextManager.markPlanTask(userId, conversationId, taskChainId, taskId,
                TASK_STATUS_FAILED, executedStep, turnId);
    }

    /** 转链整体状态（PLANNING/EXECUTING/SUCCEEDED/FAILED/CANCELLED）。 */
    public boolean transitionChain(
            String userId, String conversationId, String taskChainId, String newStatus, String turnId
    ) {
        return contextManager.transitionChainStatus(userId, conversationId, taskChainId, newStatus, turnId);
    }

    /** 组装一条已执行 step。stepNo 由调用方按 chain.steps().size()+1 给。 */
    public ConversationRuntimeContext.TaskStep buildStep(
            int stepNo,
            ConversationRuntimeContext.PlanTask task,
            String status,
            Map<String, Object> output,
            String turnId,
            LocalDateTime startedAt,
            LocalDateTime completedAt
    ) {
        return new ConversationRuntimeContext.TaskStep(
                stepNo,
                newStepId(),
                task.taskType(),
                task.taskName(),
                task.workflow(),
                status,
                turnId,
                startedAt,
                completedAt,
                output
        );
    }

    public String newChainId() {
        return "chain_" + UUID.randomUUID().toString().replace("-", "");
    }

    public String newTaskId() {
        return "task_" + UUID.randomUUID().toString().replace("-", "");
    }

    public String newStepId() {
        return "step_" + UUID.randomUUID().toString().replace("-", "");
    }
}
