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
     * 原子修改某个 step 的状态、输出、时间戳。RUNNING 时填 startedAt，终态时填 completedAt。
     * 找不到对应 stepNo 时返回 false。
     */
    boolean markChainStep(
            String userId,
            String conversationId,
            String taskChainId,
            int stepNo,
            String newStepStatus,
            ConversationRuntimeContext.StepOutput output,
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
