package com.bytedance.ai.graph.orchestration;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.api.AgentStreamEvent;
import com.bytedance.ai.graph.api.GuideGraphIntent;
import com.bytedance.ai.graph.api.GuideNodeExecutionResult;
import com.bytedance.ai.graph.api.GuideNodeResult;
import com.bytedance.ai.graph.api.NodeRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 导购节点模板，封装节点启动、成功、失败事件的通用包装逻辑。
 */
final class GuideGraphNodeTemplate {

    private static final Logger log = LoggerFactory.getLogger(GuideGraphNodeTemplate.class);

    private final Consumer<AgentStreamEvent> eventSink;

    GuideGraphNodeTemplate(Consumer<AgentStreamEvent> eventSink) {
        this.eventSink = eventSink;
    }

    Map<String, Object> execute(String nodeName, OverAllState state, GuideGraphNodeAction action) {
        String correlationId = state.value(GuideGraphStateKeys.CORRELATION_ID, "");
        Instant startedAt = Instant.now();
        eventSink.accept(GuideGraphStreamEvents.nodeStarted(correlationId, nodeName));
        try {
            GuideNodeExecutionResult businessResult = action.execute(state);
            NodeRunStatus finalStatus = businessResult.statusOverride() != null
                    ? businessResult.statusOverride()
                    : NodeRunStatus.SUCCESS;
            Instant completedAt = Instant.now();
            String errorCode = errorValue(businessResult, GuideGraphStateKeys.ERROR_CODE);
            String errorMessage = errorValue(businessResult, GuideGraphStateKeys.ERROR_MESSAGE);
            GuideNodeResult nodeResult = new GuideNodeResult(
                    nodeName,
                    finalStatus,
                    businessResult.routeIntent(),
                    errorCode,
                    errorMessage,
                    startedAt,
                    completedAt,
                    businessResult.metadata()
            );
            long latencyMs = Duration.between(startedAt, completedAt).toMillis();
            log.atInfo()
                    .addKeyValue("event.name", "guide_graph.node.completed")
                    .addKeyValue("event.outcome", finalStatus == NodeRunStatus.FAILED ? "failure" : "success")
                    .addKeyValue("node.name", nodeName)
                    .addKeyValue("node.status", finalStatus.name())
                    .addKeyValue("node.latency_ms", latencyMs)
                    .addKeyValue("node.error_code", errorCode)
                    .addKeyValue("node.metadata", businessResult.metadata())
                    .addKeyValue("rag.correlation_id", correlationId)
                    .log("guide graph node completed: nodeName={}, status={}, latencyMs={}, correlationId={}",
                            nodeName, finalStatus, latencyMs, correlationId);
            eventSink.accept(GuideGraphStreamEvents.nodeCompleted(correlationId, nodeName, latencyMs, nodeSummary(nodeResult)));
            return stateUpdate(state, nodeResult, businessResult, finalStatus);
        } catch (Exception exception) {
            // A node-level exception is captured as trace and lets the graph reach END. The stream layer
            // turns the failed final summary into a user-facing error event plus turn.completed(FAILED).
            log.atError()
                    .addKeyValue("event.name", "guide_graph.node.failed")
                    .addKeyValue("event.outcome", "failure")
                    .addKeyValue("node.name", nodeName)
                    .addKeyValue("rag.correlation_id", correlationId)
                    .setCause(exception)
                    .log("guide graph node failed: nodeName={}, correlationId={}", nodeName, correlationId);
            Instant completedAt = Instant.now();
            GuideNodeResult nodeResult = new GuideNodeResult(
                    nodeName,
                    NodeRunStatus.FAILED,
                    null,
                    "GUIDE_GRAPH_NODE_FAILED",
                    exception.getMessage(),
                    startedAt,
                    completedAt,
                    Map.of()
            );
            long latencyMs = Duration.between(startedAt, completedAt).toMillis();
            log.atError()
                    .addKeyValue("event.name", "guide_graph.node.failed_recorded")
                    .addKeyValue("event.outcome", "failure")
                    .addKeyValue("node.name", nodeName)
                    .addKeyValue("node.status", NodeRunStatus.FAILED.name())
                    .addKeyValue("node.latency_ms", latencyMs)
                    .addKeyValue("node.error_code", nodeResult.errorCode())
                    .addKeyValue("rag.correlation_id", correlationId)
                    .setCause(exception)
                    .log("guide graph node failure recorded: nodeName={}, latencyMs={}, correlationId={}",
                            nodeName, latencyMs, correlationId);
            eventSink.accept(GuideGraphStreamEvents.nodeFailed(correlationId, nodeName, latencyMs, nodeSummary(nodeResult)));
            Map<String, Object> failedUpdate = new LinkedHashMap<>();
            failedUpdate.put(GuideGraphStateKeys.LAST_NODE_RESULT, nodeResult);
            failedUpdate.put(GuideGraphStateKeys.NODE_RESULTS, appendNodeResult(state, nodeResult));
            failedUpdate.put(GuideGraphStateKeys.GRAPH_STATUS, NodeRunStatus.FAILED.name());
            return failedUpdate;
        }
    }

    private Map<String, Object> stateUpdate(
            OverAllState state,
            GuideNodeResult nodeResult,
            GuideNodeExecutionResult businessResult,
            NodeRunStatus finalStatus
    ) {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(GuideGraphStateKeys.LAST_NODE_RESULT, nodeResult);
        update.put(GuideGraphStateKeys.NODE_RESULTS, appendNodeResult(state, nodeResult));
        update.put(GuideGraphStateKeys.GRAPH_STATUS, finalStatus.name());
        if (businessResult.routeIntent() != null) {
            GuideGraphIntent routeIntent = businessResult.routeIntent();
            update.put(GuideGraphStateKeys.INTENT, routeIntent.name());
            update.put(GuideGraphStateKeys.TARGET_WORKFLOW, GuideGraphWorkflows.targetFor(routeIntent));
        }
        if (businessResult.workflowResult() != null) {
            update.put(GuideGraphStateKeys.WORKFLOW_RESULT, businessResult.workflowResult());
        }
        update.putAll(businessResult.stateUpdates());
        return update;
    }

    private List<GuideNodeResult> appendNodeResult(OverAllState state, GuideNodeResult nodeResult) {
        List<GuideNodeResult> trace = new ArrayList<>();
        state.value(GuideGraphStateKeys.NODE_RESULTS).ifPresent(value -> appendExisting(trace, value));
        trace.add(nodeResult);
        return List.copyOf(trace);
    }

    private void appendExisting(List<GuideNodeResult> trace, Object value) {
        if (value instanceof GuideNodeResult nodeResult) {
            trace.add(nodeResult);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                if (element instanceof GuideNodeResult nodeResult) {
                    trace.add(nodeResult);
                }
            }
        }
    }

    private Map<String, Object> nodeSummary(GuideNodeResult nodeResult) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", nodeResult.status().name());
        summary.put("routeIntent", nodeResult.routeIntent() == null ? null : nodeResult.routeIntent().name());
        summary.put("errorCode", nodeResult.errorCode());
        summary.put("metadata", nodeResult.metadata());
        summary.values().removeIf(value -> value == null);
        return summary;
    }

    private String errorValue(GuideNodeExecutionResult businessResult, String key) {
        Object value = businessResult.metadata().get(key);
        if (value == null) {
            value = businessResult.stateUpdates().get(key);
        }
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }
}
