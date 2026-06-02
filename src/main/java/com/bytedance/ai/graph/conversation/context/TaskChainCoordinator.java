package com.bytedance.ai.graph.conversation.context;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 任务链高层操作入口。封装常用模式（启动 / 推进 / 完成），
 * 让 planner、graph 节点不用直接操作 supersede + insert 细节。
 *
 * <p>典型流程：
 * <pre>
 *  // turn 开始：取活跃链或新建
 *  TaskChain chain = coordinator.findActiveChain(userId, convId)
 *      .orElseGet(() -&gt; coordinator.startChain(...));
 *
 *  // 取下一步并标 RUNNING
 *  TaskStep next = coordinator.nextPendingStep(chain).orElseThrow();
 *  coordinator.startStep(userId, convId, chain.taskChainId(), next.stepNo(), turnId);
 *
 *  // workflow 执行完毕
 *  coordinator.completeStep(userId, convId, chain.taskChainId(), next.stepNo(),
 *      new StepOutput("PRODUCT_CANDIDATES", Map.of("candidates", ...)), turnId);
 *
 *  // 所有 step 都 SUCCEEDED 之后
 *  coordinator.completeChain(userId, convId, chain.taskChainId(), "SUCCEEDED", turnId);
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

    public static final String STEP_STATUS_PENDING = "PENDING";
    public static final String STEP_STATUS_RUNNING = "RUNNING";
    public static final String STEP_STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STEP_STATUS_FAILED = "FAILED";
    public static final String STEP_STATUS_CANCELLED = "CANCELLED";

    private final ConversationContextManager contextManager;

    public TaskChainCoordinator(ConversationContextManager contextManager) {
        this.contextManager = contextManager;
    }

    /**
     * 新建并保存一条任务链。
     *
     * @param chainStatus 初始 chain 状态：{@link #CHAIN_STATUS_PLANNING}（等 LLM 拆步骤）或
     *                    {@link #CHAIN_STATUS_EXECUTING}（已规划好直接执行）
     * @param initialSteps PLANNING 时通常为空；EXECUTING 时至少含 1 个 PENDING step
     * @return 落库后的 chain（taskChainId 已生成）
     */
    public ConversationRuntimeContext.TaskChain startChain(
            String userId,
            String conversationId,
            String turnId,
            String sourceWorkflow,
            ConversationRuntimeContext.UserGoal userGoal,
            List<ConversationRuntimeContext.TaskStep> initialSteps,
            String chainStatus
    ) {
        LocalDateTime now = LocalDateTime.now();
        ConversationRuntimeContext.TaskChain chain = new ConversationRuntimeContext.TaskChain(
                null,
                newChainId(),
                CURRENT_SCHEMA_VERSION,
                chainStatus == null ? CHAIN_STATUS_EXECUTING : chainStatus,
                userGoal,
                initialSteps == null ? List.of() : initialSteps,
                turnId,
                turnId,
                now,
                now
        );
        contextManager.saveTaskChain(userId, conversationId, turnId, sourceWorkflow, chain, null);
        return chain;
    }

    /**
     * 找当前活跃任务链（status = PLANNING 或 EXECUTING）。
     * 多个时按 createdAt 取最近一条。
     */
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

    /**
     * 找 chain 内 stepNo 最小的 PENDING step。
     */
    public Optional<ConversationRuntimeContext.TaskStep> nextPendingStep(
            ConversationRuntimeContext.TaskChain chain
    ) {
        if (chain == null) {
            return Optional.empty();
        }
        return chain.steps().stream()
                .filter(s -> STEP_STATUS_PENDING.equals(s.status()))
                .min(Comparator.comparingInt(ConversationRuntimeContext.TaskStep::stepNo));
    }

    /** 把 step 置为 RUNNING（写 startedAt + turnId）。 */
    public boolean startStep(
            String userId,
            String conversationId,
            String taskChainId,
            int stepNo,
            String turnId
    ) {
        return contextManager.markChainStep(
                userId, conversationId, taskChainId, stepNo,
                STEP_STATUS_RUNNING, null, turnId);
    }

    /** 把 step 置为 SUCCEEDED，写入输出。 */
    public boolean completeStep(
            String userId,
            String conversationId,
            String taskChainId,
            int stepNo,
            ConversationRuntimeContext.StepOutput output,
            String turnId
    ) {
        return contextManager.markChainStep(
                userId, conversationId, taskChainId, stepNo,
                STEP_STATUS_SUCCEEDED, output, turnId);
    }

    /** 把 step 置为 FAILED，可选地写入错误描述 output。 */
    public boolean failStep(
            String userId,
            String conversationId,
            String taskChainId,
            int stepNo,
            ConversationRuntimeContext.StepOutput errorOutput,
            String turnId
    ) {
        return contextManager.markChainStep(
                userId, conversationId, taskChainId, stepNo,
                STEP_STATUS_FAILED, errorOutput, turnId);
    }

    /** 把 chain 推到终态：SUCCEEDED / FAILED / CANCELLED。 */
    public boolean completeChain(
            String userId,
            String conversationId,
            String taskChainId,
            String finalStatus,
            String turnId
    ) {
        return contextManager.transitionChainStatus(
                userId, conversationId, taskChainId, finalStatus, turnId);
    }

    /** chain id 生成器：chain_&lt;uuid-no-dash&gt;。 */
    public String newChainId() {
        return "chain_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** step id 生成器：step_&lt;uuid-no-dash&gt;。 */
    public String newStepId() {
        return "step_" + UUID.randomUUID().toString().replace("-", "");
    }
}
