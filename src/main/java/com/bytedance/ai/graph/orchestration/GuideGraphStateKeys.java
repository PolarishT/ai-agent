package com.bytedance.ai.graph.orchestration;

/**
 * 导购 Graph 状态 key 常量。
 */
public final class GuideGraphStateKeys {

    public static final String USER_ID = "userId";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String CONVERSATION_EXISTS = "conversationExists";
    public static final String CONVERSATION_INTERNAL_ID = "conversationInternalId";
    public static final String MESSAGE = "message";
    public static final String RUN_ID = "runId";
    public static final String REQUEST_ID = "requestId";
    public static final String IMAGE_REF = "imageRef";
    public static final String CORRELATION_ID = "correlationId";
    public static final String INITIAL_INTENT = "initialIntent";
    public static final String INTENT = "intent";
    public static final String MAIN_INTENT = "mainIntent";
    public static final String INTENT_CONFIDENCE = "intentConfidence";
    public static final String NEED_CLARIFY = "needClarify";
    public static final String WRITE_ACTION = "writeAction";
    public static final String TARGET_WORKFLOW = "targetWorkflow";
    public static final String INTENT_REASON = "intentReason";
    public static final String INTENT_SLOTS = "intentSlots";
    public static final String MISSING_SLOTS = "missingSlots";
    public static final String ERROR_CODE = "errorCode";
    public static final String ERROR_MESSAGE = "errorMessage";
    public static final String ROUTE_SOURCE = "routeSource";
    public static final String LLM_CALLED = "llmCalled";
    public static final String GRAPH_STATUS = "graphStatus";
    public static final String LAST_NODE_RESULT = "lastNodeResult";
    public static final String NODE_RESULTS = "nodeResults";
    public static final String WORKFLOW_RESULT = "workflowResult";
    public static final String ORDER_WORKFLOW_DISPATCHED = "orderWorkflowDispatched";
    public static final String EVIDENCE = "evidence";
    public static final String BUSINESS_RESULT = "businessResult";
    public static final String ANSWER_CONTEXT = "answerContext";
    public static final String ANSWER_STREAMED = "answerStreamed";
    public static final String WORKFLOW_STATUS = "workflow_status";
    public static final String CLARIFY_REASON = "clarify_reason";
    public static final String CART_ACTION = "cart_action";
    public static final String PRODUCT_NAME = "product_name";
    public static final String PRODUCT_CANDIDATES = "product_candidates";
    public static final String NEED_USER_INPUT = "need_user_input";
    public static final String NODE_MESSAGE = "node_message";
    public static final String RECENT_MESSAGES = "recentMessages";
    public static final String CONVERSATION_CONTEXT = "conversationContext";
    public static final String CONVERSATION_MEMORY = "conversationMemory";
    public static final String MESSAGE_COUNT = "messageCount";
    /** 前置状态机：本轮命中的活跃任务链 id 与正在执行的计划任务 id，供 buildAnswerContext 推进。 */
    public static final String ACTIVE_CHAIN_ID = "activeChainId";
    public static final String ACTIVE_TASK_ID = "activeTaskId";

    private GuideGraphStateKeys() {
    }
}
