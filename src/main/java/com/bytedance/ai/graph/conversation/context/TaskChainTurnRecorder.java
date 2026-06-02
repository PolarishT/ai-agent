package com.bytedance.ai.graph.conversation.context;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 把当前 turn 的 intent + workflow + 结果交给 {@link TaskChainPlanner} 决策，按结果落库。
 *
 * <p>Recorder 不再自己拼 chain（这部分逻辑全在 planner 里），也不再自己造 StepOutput
 * （这部分逻辑在 {@link StepOutputMapper} 里）。它的职责：
 * <ul>
 *   <li>workflow result → StepOutputMapper → 结构化 step output</li>
 *   <li>调 planner 决策最终要落库的 chain</li>
 *   <li>planner 决定要 cancel 旧链时调 {@link ConversationContextManager#transitionChainStatus}</li>
 *   <li>调 {@link ConversationContextManager#saveTaskChain} 写入新链或追加 step 后的旧链</li>
 *   <li>planner 返回 chainToPersist=null 时（META intent）整体跳过</li>
 * </ul>
 */
@Service
public class TaskChainTurnRecorder {

    /** EXECUTING 链的空闲超时（分钟）；&lt;=0 表示不开启自动过期。 */
    static final int DEFAULT_IDLE_TIMEOUT_MINUTES = 60;

    private final ConversationContextManager contextManager;
    private final TaskChainPlanner planner;
    private final StepOutputMapper stepOutputMapper;
    private final int idleTimeoutMinutes;

    public TaskChainTurnRecorder(
            ConversationContextManager contextManager,
            TaskChainPlanner planner,
            StepOutputMapper stepOutputMapper,
            @Value("${graph.agent.task-chain.idle-timeout-minutes:" + DEFAULT_IDLE_TIMEOUT_MINUTES + "}")
            int idleTimeoutMinutes
    ) {
        this.contextManager = contextManager;
        this.planner = planner;
        this.stepOutputMapper = stepOutputMapper;
        this.idleTimeoutMinutes = idleTimeoutMinutes;
    }

    /**
     * @param context 已加载好的 runtime context（包含历史 chains）；null 时仍可工作（planner 拿不到历史则只能新建）
     * @param goalText workflow 起作用时用户的原始诉求（一般用 user message）；空串/null 时 step.taskText 为 null
     * @param workflowResult workflow 节点产出的对象，原样塞到 step.output.payload.workflowResult
     * @param answerText 本轮 answer 文本，写到 step.output.payload.answer
     */
    public void recordTurn(
            ConversationRuntimeContext context,
            String userId,
            String conversationId,
            String turnId,
            String intent,
            String workflow,
            boolean success,
            String goalText,
            Object workflowResult,
            String answerText
    ) {
        if (!StringUtils.hasText(userId)
                || !StringUtils.hasText(conversationId)
                || !StringUtils.hasText(turnId)) {
            return;
        }
        String effectiveIntent = StringUtils.hasText(intent) ? intent : "UNKNOWN";
        String effectiveWorkflow = StringUtils.hasText(workflow) ? workflow : "unknown_workflow";
        String effectiveGoalText = StringUtils.hasText(goalText) ? goalText : null;

        ConversationRuntimeContext.StepOutput output = stepOutputMapper.map(
                effectiveIntent, workflowResult, answerText);

        TaskChainPlanner.TurnInput input = new TaskChainPlanner.TurnInput(
                context, turnId, effectiveIntent, effectiveWorkflow, success, effectiveGoalText, output);
        TaskChainPlanner.PlanResult result = planner.plan(input);

        if (result == null || result.chainToPersist() == null) {
            return; // planner 选择跳过（典型情况：META intent）
        }
        if (StringUtils.hasText(result.previousChainIdToCancel())) {
            contextManager.transitionChainStatus(
                    userId, conversationId, result.previousChainIdToCancel(),
                    TaskChainCoordinator.CHAIN_STATUS_CANCELLED, turnId);
        }
        contextManager.saveTaskChain(
                userId, conversationId, turnId, effectiveWorkflow,
                result.chainToPersist(),
                computeExpiresAt(result.chainToPersist()));
    }

    /**
     * 只给 EXECUTING 链打 expires_at —— 长时间没新 turn 时由 expireStaleItems 自动归档。
     * 终态（SUCCEEDED/FAILED/CANCELLED）的链保留为历史，不设过期。
     */
    private LocalDateTime computeExpiresAt(ConversationRuntimeContext.TaskChain chain) {
        if (idleTimeoutMinutes <= 0 || chain == null) {
            return null;
        }
        if (!TaskChainCoordinator.CHAIN_STATUS_EXECUTING.equals(chain.status())) {
            return null;
        }
        return LocalDateTime.now().plusMinutes(idleTimeoutMinutes);
    }
}
