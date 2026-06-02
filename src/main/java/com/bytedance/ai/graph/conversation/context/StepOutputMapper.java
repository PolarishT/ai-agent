package com.bytedance.ai.graph.conversation.context;

/**
 * 把 (intent, workflowResult, answerText) 投影成结构化的 {@link ConversationRuntimeContext.StepOutput}。
 *
 * <p>kind 字段按 intent 类别给出（PRODUCT_CANDIDATES / CART_MUTATION / ORDER_INFO / ...），
 * payload 则按已知的 workflow result 类型抽取关键字段，未知类型保留 raw。
 *
 * <p>接口放在 {@code conversation.context} 包，实现放在 {@code answer} 包以避免与各 domain workflow 形成循环依赖。
 */
public interface StepOutputMapper {

    /** kind 字典 —— 与 prompt / LLM 约定的输出种类，调用方按字符串比对。 */
    String KIND_PRODUCT_CANDIDATES = "PRODUCT_CANDIDATES";
    String KIND_PRICE_INFO = "PRICE_INFO";
    String KIND_INVENTORY_INFO = "INVENTORY_INFO";
    String KIND_CART_MUTATION = "CART_MUTATION";
    String KIND_CART_SNAPSHOT = "CART_SNAPSHOT";
    String KIND_ORDER_MUTATION = "ORDER_MUTATION";
    String KIND_ORDER_INFO = "ORDER_INFO";
    String KIND_LOGISTICS_INFO = "LOGISTICS_INFO";
    String KIND_TEXT_ANSWER = "TEXT_ANSWER";
    String KIND_CLARIFY_REQUEST = "CLARIFY_REQUEST";
    String KIND_UNKNOWN = "UNKNOWN_OUTPUT";

    ConversationRuntimeContext.StepOutput map(String intent, Object workflowResult, String answerText);
}
