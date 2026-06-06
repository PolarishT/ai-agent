package com.bytedance.ai.graph.cart.workflow;

/**
 * 购物车并发冲突异常：基于 shopping_cart.version 的乐观锁检测到其它事务已修改同一购物车。
 *
 * <p>抛出时当前事务应回滚，调用方可在读取最新购物车后重试该操作。
 */
public class CartConcurrencyConflictException extends CartWorkflowException {

    public CartConcurrencyConflictException(String message) {
        super(message);
    }
}
