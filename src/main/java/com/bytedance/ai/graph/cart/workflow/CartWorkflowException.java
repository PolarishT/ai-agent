package com.bytedance.ai.graph.cart.workflow;

/**
 * 购物车工作流异常。
 */
public class CartWorkflowException extends RuntimeException {

    public CartWorkflowException(String message) {
        super(message);
    }

    public CartWorkflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
