package com.bytedance.ai.graph.conversation.context;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Central context API. Workflows must use this manager rather than private
 * pending tables to preserve cross-workflow state.
 */
public interface ConversationContextManager {

    ConversationRuntimeContext load(String userId, String conversationId);

    void saveProductCandidates(
            String userId,
            String conversationId,
            String sourceTurnId,
            String sourceWorkflow,
            List<ConversationRuntimeContext.ProductCandidateItem> candidates,
            LocalDateTime expiresAt
    );

    void updateFocus(
            String userId,
            String conversationId,
            String sourceTurnId,
            String sourceWorkflow,
            ConversationRuntimeContext.Focus focus,
            LocalDateTime expiresAt
    );

    ConversationRuntimeContext.PendingClarification savePendingClarification(
            String userId,
            String conversationId,
            String sourceTurnId,
            String sourceWorkflow,
            ConversationRuntimeContext.PendingClarification clarification,
            LocalDateTime expiresAt
    );

    void consumePendingClarification(Long contextItemId);

    void updateCartSnapshot(
            String userId,
            String conversationId,
            String sourceTurnId,
            String sourceWorkflow,
            ConversationRuntimeContext.CartSnapshot cartSnapshot,
            LocalDateTime expiresAt
    );

    ConversationRuntimeContext.OrderContext updateOrderContext(
            String userId,
            String conversationId,
            String sourceTurnId,
            String sourceWorkflow,
            ConversationRuntimeContext.OrderContext orderContext,
            LocalDateTime expiresAt
    );

    void updateLastTurn(
            String userId,
            String conversationId,
            String sourceTurnId,
            String sourceWorkflow,
            ConversationRuntimeContext.LastTurn lastTurn,
            LocalDateTime expiresAt
    );

    boolean transitionOrderContextStatus(
            Long contextItemId,
            String expectedOrderStatus,
            ConversationRuntimeContext.OrderContext orderContext,
            ConversationContextItemStatus itemStatus
    );

    void markContextItemStatus(Long contextItemId, ConversationContextItemStatus status);

    /**
     * Upsert 一条任务链。按 chain_id 唯一定位，新版本 SUPERSEDES 旧版本。
     */
    void saveTaskChain(
            String userId,
            String conversationId,
            String sourceTurnId,
            String sourceWorkflow,
            ConversationRuntimeContext.TaskChain taskChain,
            LocalDateTime expiresAt
    );

    /**
     * 加载当前活跃版本的任务链（未 SUPERSEDED / 未过期）。找不到返回 null。
     */
    ConversationRuntimeContext.TaskChain loadTaskChain(
            String userId,
            String conversationId,
            String taskChainId
    );

    /**
     * 推进一个计划任务：把 planTasks 里 taskId 对应项置为 newTaskStatus，
     * 同时（若 executedStep 非空）把执行明细追加到 steps[]。一次原子 upsert。
     * 找不到对应 taskId 时返回 false。
     */
    boolean markPlanTask(
            String userId,
            String conversationId,
            String taskChainId,
            String taskId,
            String newTaskStatus,
            ConversationRuntimeContext.TaskStep executedStep,
            String turnId
    );

    /**
     * 修改 chain 整体 status（PLANNING / EXECUTING / SUCCEEDED / FAILED / CANCELLED）。
     */
    boolean transitionChainStatus(
            String userId,
            String conversationId,
            String taskChainId,
            String newChainStatus,
            String turnId
    );
}
