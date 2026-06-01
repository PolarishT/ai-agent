package com.bytedance.ai.graph.orchestration;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.api.GuideNodeExecutionResult;

@FunctionalInterface
public interface GuideGraphNodeAction {

    GuideNodeExecutionResult execute(OverAllState state);
}
