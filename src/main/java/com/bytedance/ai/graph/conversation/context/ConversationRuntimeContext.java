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
     * 找当前活跃任务链（status = PLANNING 或 EXECUTING），多个时取最近创建的一条。
     */
    public TaskChain activeChain() {
        TaskChain active = null;
        for (TaskChain chain : taskChains) {
            if (!"PLANNING".equals(chain.status()) && !"EXECUTING".equals(chain.status())) {
                continue;
            }
            if (active == null || isAfter(chain.createdAt(), active.createdAt())) {
                active = chain;
            }
        }
        return active;
    }

    private static boolean isAfter(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return false;
        }
        if (b == null) {
            return true;
        }
        return a.isAfter(b);
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
     * 多轮任务链：一个 user goal 的规划（planTasks）+ 执行明细（steps）。
     *
     * <p>chain.status：PLANNING / EXECUTING / SUCCEEDED / FAILED / CANCELLED
     * <ul>
     *   <li>{@code planTasks} 是 planning 阶段产出的前向计划清单，序列化为顶层 {@code taskChain}，
     *       驱动「取下一个 PENDING 任务执行」。</li>
     *   <li>{@code steps} 是已执行任务的后向明细日志，序列化进 {@code taskSummaries}。</li>
     * </ul>
     */
    public record TaskChain(
            Long contextItemId,
            String taskChainId,
            int schemaVersion,
            String status,
            UserGoal userGoal,
            List<PlanTask> planTasks,
            List<TaskStep> steps,
            String createdTurnId,
            String lastUpdatedTurnId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public TaskChain {
            planTasks = planTasks == null ? List.of() : List.copyOf(planTasks);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        /** 取最小 order 的 PENDING 计划任务（驱动执行）。无则 null。 */
        public PlanTask nextPendingTask() {
            PlanTask next = null;
            for (PlanTask t : planTasks) {
                if (!"PENDING".equals(t.status())) {
                    continue;
                }
                if (next == null || t.order() < next.order()) {
                    next = t;
                }
            }
            return next;
        }
    }

    /**
     * 计划任务：planning LLM 产出的前向清单条目。
     * 序列化给 LLM 时只暴露 {@code taskId / taskName / status}（顶层 taskChain）；
     * {@code taskType / workflow / order} 是内部路由信息，不进序列化视图。
     */
    public record PlanTask(
            String taskId,
            String taskName,
            String taskType,
            String workflow,
            int order,
            String status
    ) {
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
            Map<String, Object> output
    ) {
        public TaskStep {
            output = output == null ? Map.of() : Map.copyOf(output);
        }
    }
}
