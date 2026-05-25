package com.bytedance.ai.agent.workflow;

public enum NodeType {
    INTENT_ROUTER("intent_router"),
    SLOT_FILLING("slot_filling"),
    RAG_QA("rag_qa"),
    PRODUCT_SEARCH("product_search"),
    PRODUCT_RANK("product_rank"),
    PRODUCT_FILTER("product_filter"),
    PRODUCT_COMPARE("product_compare"),
    INVENTORY_CHECK("inventory_check"),
    HUMAN_CONFIRM("human_confirm"),
    ORDER_CREATE("order_create"),
    FINAL_ANSWER("final_answer"),
    END("end");

    private final String wireName;

    NodeType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static NodeType fromWireName(String value) {
        for (NodeType type : values()) {
            if (type.wireName.equals(value) || type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown workflow node type: " + value);
    }
}
