package com.bytedance.ai.graph.api;

import java.time.Instant;
import java.util.Map;

/**
 * 导购节点对外展示结果，供流式事件和最终摘要复用。
 */
public record GuideNodeResult(
        String nodeName,
        NodeRunStatus status,
        GuideGraphIntent routeIntent,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Map<String, Object> metadata
) {
    public GuideNodeResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
