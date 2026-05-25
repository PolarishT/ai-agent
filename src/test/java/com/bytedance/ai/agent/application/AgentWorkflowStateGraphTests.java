package com.bytedance.ai.agent.application;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorkflowStateGraphTests {

    @Test
    void compilesMinimalStateGraph() throws Exception {
        KeyStrategyFactory keyStrategyFactory = new KeyStrategyFactoryBuilder()
                .defaultStrategy(new ReplaceStrategy())
                .build();
        StateGraph graph = new StateGraph("minimal-agent-workflow", keyStrategyFactory);
        graph.addNode("dummy", AsyncNodeAction.node_async(state -> Map.of("ok", true)));
        graph.addEdge(StateGraph.START, "dummy");
        graph.addEdge("dummy", StateGraph.END);

        CompiledGraph compiledGraph = graph.compile();
        OverAllState state = compiledGraph.invoke(Map.of("input", "hello")).orElseThrow();

        assertThat(state.<Boolean>value("ok").orElse(false)).isTrue();
    }
}
