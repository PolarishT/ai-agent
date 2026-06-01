package com.bytedance.ai.graph.ordermanage;

import java.util.List;

/**
 * 订单管理流程执行结果。
 */
public record AddressParseResult(
        boolean complete,
        List<String> missingFields,
        AddressSnapshot snapshot
) {
}
