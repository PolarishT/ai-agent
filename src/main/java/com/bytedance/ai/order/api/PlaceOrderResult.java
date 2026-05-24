package com.bytedance.ai.order.api;

import java.util.List;

public record PlaceOrderResult(
        boolean placed,
        boolean confirmationRequired,
        String code,
        String message,
        OrderView order,
        List<PriceChangeView> priceChanges
) {
    public static PlaceOrderResult placed(OrderView order) {
        return new PlaceOrderResult(true, false, "ORDER_PLACED", "订单已提交", order, List.of());
    }

    public static PlaceOrderResult confirmationRequired(List<PriceChangeView> priceChanges) {
        return new PlaceOrderResult(
                false,
                true,
                "PRICE_CHANGED_CONFIRM_REQUIRED",
                "商品价格已变化，请确认后再下单",
                null,
                priceChanges == null ? List.of() : List.copyOf(priceChanges)
        );
    }
}
