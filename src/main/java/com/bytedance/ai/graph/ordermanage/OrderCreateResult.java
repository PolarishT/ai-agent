package com.bytedance.ai.graph.ordermanage;

/**
 * 订单管理流程执行结果。
 */
public record OrderCreateResult(
        boolean success,
        String orderNo,
        String message,
        OrderManageStatus status
) {
    public static OrderCreateResult success(String orderNo) {
        return new OrderCreateResult(
                true,
                orderNo,
                "订单已提交成功，订单号：" + orderNo + "。当前为模拟下单，未进行真实支付。",
                OrderManageStatus.ORDER_CREATED
        );
    }

    public static OrderCreateResult failure(String message, OrderManageStatus status) {
        return new OrderCreateResult(false, null, message, status);
    }
}
