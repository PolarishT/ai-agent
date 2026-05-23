package com.bytedance.ai.agent.slot;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;

public interface SlotExtractor {

    Slot extract(String message, IntentType intent);
}
