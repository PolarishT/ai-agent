package com.bytedance.ai.graph.api
;

import java.util.List;

/**
 * 导购 Graph 结束摘要工厂，统一把执行状态转换成面向前端的最终摘要。
 */
public final class GuideGraphFinalSummaryFactory {

    private GuideGraphFinalSummaryFactory() {
    }

    public static GuideGraphFinalSummary fromState(
            GuideGraphRequest request,
            GuideGraphIntent intent,
            String targetWorkflow,
            List<GuideNodeResult> nodeResults,
            Object workflowResult
    ) {
        NodeRunStatus finalStatus = calculateFinalStatus(nodeResults, workflowResult);
        String finalNode = lastNodeName(nodeResults);

        return GuideGraphFinalSummary.success(
                request,
                intent,
                targetWorkflow,
                finalStatus,
                finalNode
        );
    }

    public static NodeRunStatus calculateFinalStatus(
            List<GuideNodeResult> nodeResults,
            Object workflowResult
    ) {
        if (hasFailedNode(nodeResults)) {
            return NodeRunStatus.FAILED;
        }

        NodeRunStatus workflowStatus = extractWorkflowStatus(workflowResult);

        if (workflowStatus == NodeRunStatus.FAILED) {
            return NodeRunStatus.FAILED;
        }

        if (workflowStatus == NodeRunStatus.WAITING_CLARIFICATION) {
            return NodeRunStatus.WAITING_CLARIFICATION;
        }

        if (workflowStatus == NodeRunStatus.WAITING_CONFIRMATION) {
            return NodeRunStatus.WAITING_CONFIRMATION;
        }

        return NodeRunStatus.SUCCESS;
    }

    private static boolean hasFailedNode(List<GuideNodeResult> nodeResults) {
        return nodeResults != null && nodeResults.stream()
                .anyMatch(node -> node.status() == NodeRunStatus.FAILED);
    }

    private static String lastNodeName(List<GuideNodeResult> nodeResults) {
        return nodeResults == null || nodeResults.isEmpty()
                ? null
                : nodeResults.get(nodeResults.size() - 1).nodeName();
    }

    private static NodeRunStatus extractWorkflowStatus(Object workflowResult) {
        if (workflowResult instanceof HasNodeRunStatus hasStatus) {
            return hasStatus.status();
        }

        if (workflowResult instanceof NodeRunStatus status) {
            return status;
        }

        return null;
    }

    public interface HasNodeRunStatus {
        NodeRunStatus status();
    }
}