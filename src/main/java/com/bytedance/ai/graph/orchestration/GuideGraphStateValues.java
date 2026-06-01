package com.bytedance.ai.graph.orchestration;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.api.GuideGraphIntent;
import com.bytedance.ai.graph.api.NodeRunStatus;

import java.util.List;
import java.util.Optional;

/**
 * 导购 Graph 状态值常量。
 */
final class GuideGraphStateValues {

    private GuideGraphStateValues() {
    }

    static Optional<GuideGraphIntent> intent(OverAllState state, String key) {
        return state.value(key).flatMap(GuideGraphStateValues::intentFromObject);
    }

    static Optional<NodeRunStatus> status(OverAllState state, String key) {
        return state.value(key).flatMap(GuideGraphStateValues::statusFromObject);
    }

    private static Optional<GuideGraphIntent> intentFromObject(Object value) {
        if (value instanceof GuideGraphIntent intent) {
            return Optional.of(intent);
        }
        return GuideGraphIntent.parse(enumName(value));
    }

    private static Optional<NodeRunStatus> statusFromObject(Object value) {
        if (value instanceof NodeRunStatus status) {
            return Optional.of(status);
        }
        try {
            return Optional.of(NodeRunStatus.valueOf(enumName(value)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static String enumName(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            Object last = list.getLast();
            return last == null ? "" : last.toString();
        }
        return value == null ? "" : value.toString();
    }
}
