package com.bytedance.ai.graph.intent;

import java.util.List;
import java.util.Map;

/**
 * 主意图识别结果，包含意图、置信度和槽位。
 */
public record MainIntentDecision(
        MainIntent intent,
        double confidence,
        boolean needClarify,
        boolean writeAction,
        String targetWorkflow,
        String reason,
        Map<String, Object> slots,
        List<String> missingSlots
) {

    public static MainIntentDecision clarify(String reason) {
        return new MainIntentDecision(
                MainIntent.CLARIFY,
                0.0d,
                true,
                false,
                "clarify_workflow",
                reason,
                Map.of(),
                List.of()
        );
    }
}
