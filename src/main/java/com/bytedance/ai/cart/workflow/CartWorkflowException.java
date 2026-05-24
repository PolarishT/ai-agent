package com.bytedance.ai.cart.workflow;

public class CartWorkflowException extends RuntimeException {

    public CartWorkflowException(String message) {
        super(message);
    }

    public CartWorkflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
