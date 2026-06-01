package com.bytedance.ai.graph.order.api;

/**
 * 订单对外只读视图。
 */
public record DeliveryAddressView(
        Long id,
        String receiverName,
        String phone,
        String province,
        String city,
        String district,
        String detail,
        String postalCode
) {
}
