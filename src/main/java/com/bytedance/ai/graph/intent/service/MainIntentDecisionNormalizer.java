package com.bytedance.ai.graph.intent.service;

import com.bytedance.ai.graph.intent.MainIntentWorkflowMapping;
import com.bytedance.ai.graph.intent.MainIntentDecision;
import com.bytedance.ai.graph.intent.MainIntent;
import com.bytedance.ai.graph.intent.support.SlotKeyNormalizer;
import com.bytedance.ai.graph.intent.support.SlotKeys;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MainIntentDecisionNormalizer {

    private static final double MIN_CONFIDENCE = 0.55d;

    public MainIntentDecision normalize(MainIntentDecision raw) {
        if (raw == null) {
            return MainIntentDecision.clarify("Intent LLM returned empty decision");
        }
        Map<String, Object> slots = SlotKeyNormalizer.normalize(raw.slots());
        List<String> missingSlots = raw.missingSlots() == null ? List.of() : List.copyOf(raw.missingSlots());
        MainIntent intent = raw.intent() == null ? MainIntent.UNKNOWN : raw.intent();
        double confidence = clamp(raw.confidence());
        if (confidence < MIN_CONFIDENCE) {
            intent = MainIntent.CLARIFY;
        }
        List<String> normalizedMissingSlots = normalizeMissingSlots(intent, slots, missingSlots);

        // Legacy granular cart intents are fully rewritten to CART_MANAGE before reaching the
        // workflow layer. The original action lives on as the `cart_action` slot so the
        // cart_manage_workflow's slot merger can dispatch without re-asking the LLM.
        String legacyCartAction = legacyCartActionOf(intent);
        if (legacyCartAction != null) {
            intent = MainIntent.CART_MANAGE;
            Map<String, Object> withCartAction = new LinkedHashMap<>(slots);
            withCartAction.putIfAbsent(SlotKeys.CART_ACTION, legacyCartAction);
            slots = Map.copyOf(withCartAction);
            // CART_MANAGE is not in requiresKeySlots — the workflow itself decides whether to
            // ask for clarification, so drop any main-intent missingSlots judgement that was
            // computed against the now-replaced legacy intent.
            normalizedMissingSlots = List.of();
        }

        boolean needClarify = intent == MainIntent.CLARIFY
                || intent == MainIntent.UNKNOWN
                || (!normalizedMissingSlots.isEmpty() && requiresKeySlots(intent));
        boolean writeAction = isWriteAction(intent);
        return new MainIntentDecision(
                intent,
                confidence,
                needClarify,
                writeAction,
                MainIntentWorkflowMapping.targetWorkflowOf(intent),
                raw.reason() == null ? "" : raw.reason(),
                slots,
                normalizedMissingSlots
        );
    }

    private List<String> normalizeMissingSlots(MainIntent intent, Map<String, Object> slots, List<String> missingSlots) {
        if (!missingSlots.isEmpty() || !requiresKeySlots(intent)) {
            return missingSlots;
        }
        List<String> computed = new ArrayList<>();
        switch (intent) {
            case ADD_TO_CART -> requireAny(slots, computed,
                    SlotKeys.PRODUCT_REF, SlotKeys.PRODUCT_ID, SlotKeys.SKU_ID);
            case REMOVE_FROM_CART -> requireAny(slots, computed,
                    SlotKeys.CART_ITEM_ID, SlotKeys.PRODUCT_REF);
            case UPDATE_CART_ITEM -> {
                requireAny(slots, computed, SlotKeys.CART_ITEM_ID, SlotKeys.PRODUCT_REF);
                requireAny(slots, computed, SlotKeys.QUANTITY, SlotKeys.SKU_ID, SlotKeys.SPEC);
            }
            case CREATE_ORDER -> requireAny(slots, computed,
                    "cartId", "conversationCartContext", "conversationCart");
            case CONFIRM_ORDER -> requireAny(slots, computed, "pendingOrderId", "orderDraftId");
            case CANCEL_ORDER -> requireAny(slots, computed, "orderId");
            default -> {
            }
        }
        return computed.isEmpty() ? List.of() : List.copyOf(computed);
    }

    private String legacyCartActionOf(MainIntent intent) {
        if (intent == null) {
            return null;
        }
        return switch (intent) {
            case ADD_TO_CART -> SlotKeys.CART_ACTION_ADD;
            case REMOVE_FROM_CART -> SlotKeys.CART_ACTION_REMOVE;
            case UPDATE_CART_ITEM -> SlotKeys.CART_ACTION_UPDATE_QUANTITY;
            default -> null;
        };
    }

    private void requireAny(Map<String, Object> slots, List<String> missingSlots, String... keys) {
        for (String key : keys) {
            Object value = slots.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return;
            }
        }
        missingSlots.add(String.join("|", keys));
    }

    private boolean requiresKeySlots(MainIntent intent) {
        // CART_MANAGE intentionally not listed: slot filling is performed inside cart_manage_workflow
        // (CartManageSlotFillingService), not by the main intent LLM.
        return switch (intent) {
            case ADD_TO_CART, REMOVE_FROM_CART, UPDATE_CART_ITEM, CREATE_ORDER, CONFIRM_ORDER, CANCEL_ORDER -> true;
            default -> false;
        };
    }

    private boolean isWriteAction(MainIntent intent) {
        return switch (intent) {
            case ADD_TO_CART, REMOVE_FROM_CART, UPDATE_CART_ITEM, CART_MANAGE,
                 CREATE_ORDER, CONFIRM_ORDER, CANCEL_ORDER -> true;
            default -> false;
        };
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || value < 0.0d) {
            return 0.0d;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return value;
    }
}
