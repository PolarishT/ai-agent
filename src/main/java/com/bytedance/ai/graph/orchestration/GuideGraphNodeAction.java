package com.bytedance.ai.graph.orchestration;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.api.GuideNodeExecutionResult;

/**
 * 导购节点动作接口，统一约束 StateGraph 节点的执行入口。
 */
@FunctionalInterface
public interface GuideGraphNodeAction {

    GuideNodeExecutionResult execute(OverAllState state);
}
