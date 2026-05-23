package com.bytedance.ai.agent.intent;

import com.bytedance.ai.agent.api.IntentType;

public record IntentClassification(
        IntentType intent,
        double confidence,
        String source
) {
}
