package com.bytedance.ai.cart.workflow;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public record CartCommand(
        String userId,
        String conversationId,
        Long spuId,
        String externalRef,
        Integer quantity,
        BigDecimal expectedUnitPrice,
        Map<String, Object> shippingAddress,
        String triggeredBy,
        String failureReason,
        String errorMessage,
        Map<String, Object> metadata
) {

    public CartCommand {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        shippingAddress = shippingAddress == null ? Map.of() : Map.copyOf(shippingAddress);
    }

    public static CartCommand of(String userId, String conversationId, Long spuId, String externalRef, Integer quantity) {
        return new CartCommand(
                userId,
                conversationId,
                spuId,
                externalRef,
                quantity,
                null,
                Map.of(),
                "agent",
                null,
                null,
                new LinkedHashMap<>()
        );
    }
}
