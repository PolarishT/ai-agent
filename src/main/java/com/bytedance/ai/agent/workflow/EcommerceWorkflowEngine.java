package com.bytedance.ai.agent.workflow;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.bytedance.ai.agent.api.AgentStreamEvent;
import com.bytedance.ai.agent.api.AgentTurnRequest;
import com.bytedance.ai.agent.api.CompareMatrixView;
import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.api.ToolCallView;
import com.bytedance.ai.agent.application.AgentSseEventFactory;
import com.bytedance.ai.agent.memory.ConversationMemory;
import com.bytedance.ai.agent.memory.ConversationMemoryLoader;
import com.bytedance.ai.agent.memory.ConversationSummarizer;
import com.bytedance.ai.agent.memory.ConversationSummary;
import com.bytedance.ai.retrieval.spi.AgentTurnConversationState;
import com.bytedance.ai.shared.support.RagLogFields;
import com.bytedance.ai.shared.support.RagLogHelper;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EcommerceWorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(EcommerceWorkflowEngine.class);

    private final WorkflowDefinitionLoader definitionLoader;
    private final WorkflowStateStore stateStore;
    private final NodeExecutorRegistry nodeExecutorRegistry;
    private final EdgeResolver edgeResolver;
    private final ConversationMemoryLoader memoryLoader;
    private final ConversationSummarizer conversationSummarizer;
    private final AgentSseEventFactory eventFactory;
    private final ResumeInputParser resumeInputParser;

    public EcommerceWorkflowEngine(
            WorkflowDefinitionLoader definitionLoader,
            WorkflowStateStore stateStore,
            NodeExecutorRegistry nodeExecutorRegistry,
            EdgeResolver edgeResolver,
            ConversationMemoryLoader memoryLoader,
            ConversationSummarizer conversationSummarizer,
            AgentSseEventFactory eventFactory,
            ResumeInputParser resumeInputParser
    ) {
        this.definitionLoader = definitionLoader;
        this.stateStore = stateStore;
        this.nodeExecutorRegistry = nodeExecutorRegistry;
        this.edgeResolver = edgeResolver;
        this.memoryLoader = memoryLoader;
        this.conversationSummarizer = conversationSummarizer;
        this.eventFactory = eventFactory;
        this.resumeInputParser = resumeInputParser;
    }

    public Flux<WorkflowSignal> run(WorkflowRequest request) {
        return Flux.create(sink -> {
            WorkflowDefinition definition = definitionLoader.loadDefault();
            WorkflowRuntimeState runtimeState = stateStore.restore(request.request().conversationId());
            runtimeState.userId(request.request().userId());
            String entryNode = resolveEntryNode(definition, runtimeState, request);
            prepareMemory(request, runtimeState);
            WorkflowExecution execution = new WorkflowExecution(
                    request,
                    request.conversationState(),
                    runtimeState,
                    eventFactory,
                    sink
            );
            try {
                if (entryNode == null) {
                    stateStore.save(request.request().conversationId(), runtimeState);
                    sink.next(WorkflowSignal.result(toResult(runtimeState)));
                    sink.complete();
                    return;
                }
                CompiledGraph graph = compile(definition, execution, entryNode);
                graph.invoke(Map.of("workflowId", definition.id(), "turnId", request.turnId(), "entryNode", entryNode),
                        RunnableConfig.builder().threadId(request.turnId()).build());
                if (runtimeState.status() == WorkflowStatus.END) {
                    stateStore.clear(request.request().conversationId());
                } else {
                    stateStore.save(request.request().conversationId(), runtimeState);
                }
                sink.next(WorkflowSignal.result(toResult(runtimeState)));
                sink.complete();
            } catch (Throwable exception) {
                runtimeState.status(WorkflowStatus.FAILED);
                stateStore.save(request.request().conversationId(), runtimeState);
                sink.error(exception);
            }
        });
    }

    public CompiledGraph compileForTest() {
        WorkflowDefinition definition = definitionLoader.loadDefault();
        return compile(definition, null, definition.startNode());
    }

    String resolveEntryNode(WorkflowDefinition definition, WorkflowRuntimeState state, WorkflowRequest request) {
        String message = request.request() == null ? null : request.request().message();
        if (state.isPaused()) {
            resumeInputParser.apply(state, message);
            if (state.isPaused()) {
                // user message did not satisfy the pause — re-pause without re-running the graph.
                return null;
            }
            String resumeNode = resolvePauseResumeNode(state);
            if (resumeNode != null) {
                // 保留 pendingSelection / pendingConfirmation —— 由 executor 消费后再 clear，
                // 否则 inventory_check / order_create 拿不到 selectedExternalRef / confirmed 信号。
                state.currentNode(null);
                return resumeNode;
            }
        }
        if (state.currentNode() != null && state.status() == WorkflowStatus.RUNNING) {
            String resume = state.currentNode();
            state.currentNode(null);
            return resume;
        }
        state.currentNode(null);
        return definition.startNode();
    }

    private String resolvePauseResumeNode(WorkflowRuntimeState state) {
        // The pause that was *just* satisfied dictates the resume node. We key off the persisted
        // currentNode (= the node that paused) so a fresh confirmation does not re-trigger an
        // older, already-resolved selection.
        String paused = state.currentNode();
        if (paused != null) {
            if (state.pendingConfirmation() != null && state.pendingConfirmation().confirmed()
                    && paused.equals(state.pendingConfirmation().sourceNode())) {
                return state.pendingConfirmation().resumeNode();
            }
            if (state.pendingSelection() != null && state.pendingSelection().resolved()
                    && paused.equals(state.pendingSelection().sourceNode())) {
                return state.pendingSelection().resumeNode();
            }
            if (state.pendingSlot() != null && paused.equals(state.pendingSlot().sourceNode())) {
                return state.pendingSlot().resumeNode();
            }
        }
        if (state.pendingConfirmation() != null && state.pendingConfirmation().confirmed()) {
            return state.pendingConfirmation().resumeNode();
        }
        if (state.pendingSelection() != null && state.pendingSelection().resolved()) {
            return state.pendingSelection().resumeNode();
        }
        if (state.pendingSlot() != null) {
            return state.pendingSlot().resumeNode();
        }
        return state.currentNode();
    }

    private void prepareMemory(WorkflowRequest request, WorkflowRuntimeState runtimeState) {
        ConversationMemory memory = memoryLoader.load(request.request().conversationId(), request.conversationState().history());
        ConversationSummary summary = conversationSummarizer.summarize(
                request.conversationState().history(),
                memory.summary(),
                memory.summaryMessageCount()
        );
        runtimeState.memory(memory.withSummary(summary));
        runtimeState.memorySummary(summary);
    }

    private CompiledGraph compile(WorkflowDefinition definition, WorkflowExecution execution, String entryNode) {
        try {
            validateDefinition(definition);
            KeyStrategyFactory keyStrategyFactory = new KeyStrategyFactoryBuilder()
                    .defaultStrategy(new ReplaceStrategy())
                    .build();
            StateGraph graph = new StateGraph(definition.id(), keyStrategyFactory);
            for (WorkflowDefinition.WorkflowNodeDefinition node : definition.nodes()) {
                graph.addNode(node.id(), AsyncNodeAction.node_async(state -> executeNode(definition, execution, node)));
            }
            graph.addEdge(StateGraph.START, entryNode != null ? entryNode : definition.startNode());
            for (WorkflowDefinition.WorkflowNodeDefinition node : definition.nodes()) {
                if (node.nodeType() == NodeType.END) {
                    graph.addEdge(node.id(), StateGraph.END);
                    continue;
                }
                Map<String, String> mappings = edgeMappings(definition);
                graph.addConditionalEdges(node.id(),
                        AsyncEdgeAction.edge_async(state -> edgeResolver.resolve(definition, node, execution.state())),
                        mappings);
            }
            return graph.compile();
        } catch (Exception exception) {
            throw new IllegalStateException("Spring AI Alibaba ecommerce workflow compile failed", exception);
        }
    }

    private Map<String, Object> executeNode(
            WorkflowDefinition definition,
            WorkflowExecution execution,
            WorkflowDefinition.WorkflowNodeDefinition node
    ) throws Exception {
        if (execution == null) {
            return Map.of("compiled", true);
        }
        execution.emit(eventFactory.workflowNodeStarted(execution.correlationId(), node.id()));
        execution.state().currentNode(node.id());
        long started = System.nanoTime();
        try {
            Map<String, Object> output = nodeExecutorRegistry.get(node.nodeType()).execute(execution, node);
            long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            execution.emit(eventFactory.workflowNodeCompleted(execution.correlationId(), node.id(), latencyMs, nodeSummary(execution)));
            logNodeCompleted(execution, node.id(), latencyMs);
            return output;
        } catch (Exception exception) {
            execution.state().status(WorkflowStatus.FAILED);
            logNodeFailed(execution, node.id(), exception);
            throw exception;
        }
    }

    private void validateDefinition(WorkflowDefinition definition) {
        for (WorkflowDefinition.WorkflowNodeDefinition node : definition.nodes()) {
            nodeExecutorRegistry.get(node.nodeType());
            if (requiresTool(node.nodeType()) && node.tool() == null) {
                throw new IllegalArgumentException("Workflow node requires tool binding: " + node.id());
            }
            if (node.tool() != null && (node.tool().inputSchema().isEmpty() || node.tool().outputSchema().isEmpty())) {
                throw new IllegalArgumentException("Workflow tool binding requires input/output schema: " + node.id());
            }
        }
    }

    private boolean requiresTool(NodeType type) {
        return type == NodeType.PRODUCT_SEARCH
                || type == NodeType.PRODUCT_COMPARE
                || type == NodeType.INVENTORY_CHECK
                || type == NodeType.ORDER_CREATE;
    }

    private Map<String, String> edgeMappings(WorkflowDefinition definition) {
        Map<String, String> mappings = new HashMap<>();
        for (WorkflowDefinition.WorkflowNodeDefinition node : definition.nodes()) {
            mappings.put(node.id(), node.id());
        }
        mappings.put(EdgeResolver.TERMINAL, StateGraph.END);
        return mappings;
    }

    private WorkflowResult toResult(WorkflowRuntimeState state) {
        return new WorkflowResult(
                state.intent(),
                state.slots(),
                state.toolCalls(),
                state.cards(),
                state.compareMatrix(),
                state.answerText(),
                state.generatedByModel().get(),
                state.memorySummary(),
                state.status()
        );
    }

    private Map<String, Object> nodeSummary(WorkflowExecution execution) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", execution.state().status().name());
        summary.put("targetNode", execution.state().targetNode());
        summary.put("cardsCount", execution.state().cards().size());
        summary.values().removeIf(value -> value == null);
        return summary;
    }

    private void logNodeCompleted(WorkflowExecution execution, String nodeName, long latencyMs) {
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "agent.workflow.node.completed")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SUCCESS)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, execution.correlationId())
                .addKeyValue("agent.turn_id", execution.request().turnId())
                .addKeyValue("agent.workflow_node", nodeName)
                .addKeyValue(RagLogFields.RAG_ELAPSED_MS, latencyMs)
                .log("agent.workflow node completed: turnId={}, node={}, tookMs={}",
                        execution.request().turnId(), nodeName, latencyMs);
    }

    private void logNodeFailed(WorkflowExecution execution, String nodeName, Throwable exception) {
        log.atWarn()
                .addKeyValue(RagLogFields.EVENT_NAME, "agent.workflow.node.failed")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_FAILURE)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, execution.correlationId())
                .addKeyValue("agent.turn_id", execution.request().turnId())
                .addKeyValue("agent.workflow_node", nodeName)
                .addKeyValue(RagLogFields.RAG_ERROR_SUMMARY, RagLogHelper.errorSummary(exception))
                .log("agent.workflow node failed: turnId={}, node={}, error={}",
                        execution.request().turnId(), nodeName, RagLogHelper.errorSummary(exception));
    }

    public record WorkflowRequest(
            AgentTurnRequest request,
            String turnId,
            String correlationId,
            AgentTurnConversationState conversationState
    ) {
    }

    public record WorkflowResult(
            IntentType intent,
            Slot slots,
            List<ToolCallView> toolCalls,
            List<SpuCardView> cards,
            CompareMatrixView compareMatrix,
            String answerText,
            boolean generatedByModel,
            ConversationSummary memorySummary,
            WorkflowStatus status
    ) {
        public WorkflowResult {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            cards = cards == null ? List.of() : List.copyOf(cards);
            answerText = answerText == null ? "" : answerText;
        }
    }

    public record WorkflowSignal(AgentStreamEvent event, WorkflowResult result) {
        static WorkflowSignal event(AgentStreamEvent event) {
            return new WorkflowSignal(event, null);
        }

        static WorkflowSignal result(WorkflowResult result) {
            return new WorkflowSignal(null, result);
        }
    }
}
