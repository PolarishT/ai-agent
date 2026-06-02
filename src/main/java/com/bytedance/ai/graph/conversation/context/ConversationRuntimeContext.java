package com.bytedance.ai.graph.conversation.context;

import com.bytedance.ai.graph.conversation.ConversationMessage;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Per-turn conversation context loaded once by the main guide graph.
 */
public record ConversationRuntimeContext(
        Long conversationInternalId,
        String userId,
        String conversationId,
        List<ConversationMessage> recentMessages,
        Focus focus,
        List<ProductCandidateItem> productCandidates,
        CartSnapshot cart,
        OrderContext order,
        PendingClarification pendingClarification,
        LastTurn lastTurn,
        Map<String, LastTurn> lastResults,
        Map<String, Object> memorySlots,
        List<TaskChain> taskChains
) {

    public ConversationRuntimeContext {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        productCandidates = productCandidates == null ? List.of() : List.copyOf(productCandidates);
        lastResults = lastResults == null ? Map.of() : Map.copyOf(lastResults);
        memorySlots = memorySlots == null ? Map.of() : Map.copyOf(memorySlots);
        taskChains = taskChains == null ? List.of() : List.copyOf(taskChains);
    }

    public static ConversationRuntimeContext empty(
            Long conversationInternalId,
            String userId,
            String conversationId,
            List<ConversationMessage> recentMessages
    ) {
        return new ConversationRuntimeContext(
                conversationInternalId,
                userId,
                conversationId,
                recentMessages,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                List.of()
        );
    }

    public LastTurn lastResult(String workflow) {
        if (workflow == null || workflow.isBlank()) {
            return null;
        }
        return lastResults.get(workflow);
    }

    public String conversationMemoryText() {
        if (recentMessages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ConversationMessage message : recentMessages) {
            builder.append(message.role()).append(": ").append(message.content()).append('\n');
        }
        return builder.toString().trim();
    }

    /**
     * LLM 友好的多轮记忆摘要：最近消息 + 任务链状态。
     * 用于在 answer 生成 / planner / build_answer_context 阶段把跨轮信息塞进 prompt。
     * 空上下文返回空串。
     */
    public String agentMemoryText() {
        StringBuilder builder = new StringBuilder();
        if (!recentMessages.isEmpty()) {
            builder.append("[Recent messages]\n");
            for (ConversationMessage message : recentMessages) {
                builder.append(message.role()).append(": ").append(message.content()).append('\n');
            }
        }
        if (!taskChains.isEmpty()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("[Task chains]\n");
            for (TaskChain chain : taskChains) {
                builder.append("- chain ").append(chain.taskChainId())
                        .append(" (").append(chain.status()).append(")");
                if (chain.userGoal() != null && chain.userGoal().goalText() != null) {
                    builder.append(": ").append(chain.userGoal().goalText());
                }
                builder.append('\n');
                for (TaskStep step : chain.steps()) {
                    builder.append("  step ").append(step.stepNo())
                            .append(" [").append(step.taskType())
                            .append(" @").append(step.workflow()).append("] ")
                            .append(step.status());
                    if (step.output() != null && step.output().kind() != null) {
                        builder.append(" -> ").append(step.output().kind());
                    }
                    builder.append('\n');
                }
            }
        }
        return builder.toString().trim();
    }

    public record ProductCandidateItem(
            Long contextItemId,
            int rank,
            String productId,
            String skuId,
            String productName,
            BigDecimal price,
            String brief,
            String spec,
            String externalRef,
            Integer stock,
            Map<String, Object> payload
    ) {
        public ProductCandidateItem {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    public record Focus(
            Long contextItemId,
            String itemKey,
            Integer rank,
            String productId,
            String skuId,
            String productName,
            Map<String, Object> payload
    ) {
        public Focus {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    public record CartSnapshot(
            Long contextItemId,
            Map<String, Object> payload
    ) {
        public CartSnapshot {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    public record OrderContext(
            Long contextItemId,
            Map<String, Object> cartSnapshot,
            String cartSnapshotHash,
            Map<String, Object> addressSnapshot,
            BigDecimal amountSnapshot,
            String orderStatus,
            String failReason,
            String orderNo,
            LocalDateTime expiresAt,
            Map<String, Object> payload
    ) {
        public OrderContext {
            cartSnapshot = cartSnapshot == null ? Map.of() : Map.copyOf(cartSnapshot);
            addressSnapshot = addressSnapshot == null ? Map.of() : Map.copyOf(addressSnapshot);
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    public record PendingClarification(
            Long contextItemId,
            String clarificationType,
            String sourceWorkflow,
            Integer quantity,
            List<ProductCandidateItem> candidates,
            LocalDateTime expiresAt,
            Map<String, Object> payload
    ) {
        public PendingClarification {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    public record LastTurn(
            Long contextItemId,
            String sourceWorkflow,
            String status,
            Map<String, Object> payload
    ) {
        public LastTurn {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    /**
     * 多轮任务链：一个 user goal 的规划 + 执行历史。
     * chain.status：PLANNING / EXECUTING / SUCCEEDED / FAILED / CANCELLED
     * steps 中 PENDING/RUNNING 的部分构成"当前计划"，SUCCEEDED/FAILED 的部分是历史。
     */
    public record TaskChain(
            Long contextItemId,
            String taskChainId,
            int schemaVersion,
            String status,
            UserGoal userGoal,
            List<TaskStep> steps,
            String createdTurnId,
            String lastUpdatedTurnId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public TaskChain {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    public record UserGoal(
            String goalType,
            String goalText,
            boolean isComposite,
            String status
    ) {
    }

    public record TaskStep(
            int stepNo,
            String stepId,
            String taskType,
            String taskText,
            String workflow,
            String status,
            String turnId,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            StepOutput output
    ) {
    }

    /**
     * Step 输出，按 kind 区分子结构（PRODUCT_CANDIDATES / CART_MUTATION / ORDER_INFO / TEXT_ANSWER / ...）。
     * payload 是离散字段，序列化给 LLM 时按 kind 解释。
     */
    public record StepOutput(
            String kind,
            Map<String, Object> payload
    ) {
        public StepOutput {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }
}
