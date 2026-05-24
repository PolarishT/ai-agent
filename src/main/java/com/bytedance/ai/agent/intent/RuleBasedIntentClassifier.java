package com.bytedance.ai.agent.intent;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.memory.ConversationMemory;
import org.springframework.util.StringUtils;

/**
 * Intent 离线兜底分类器。
 *
 * <p>不注册为 Spring Bean；线上主路径使用 {@link LlmIntentClassifier}。
 * 这里的正则只在 ChatModel 缺席或 LLM 输出异常时兜底，避免 demo 环境完全不可用。
 */
public class RuleBasedIntentClassifier implements IntentClassifier {

    @Override
    public IntentClassification classify(String message, ConversationMemory memory) {
        String normalized = normalize(message);
        if (!StringUtils.hasText(normalized)) {
            return fallback();
        }
        if (IntentRules.OUT_OF_SCOPE.matcher(normalized).matches()) {
            return new IntentClassification(IntentType.OUT_OF_SCOPE, 0.7d, "fallback");
        }
        if (IntentRules.COMPARE.matcher(normalized).matches()) {
            return new IntentClassification(IntentType.COMPARE, 0.7d, "fallback");
        }
        if (IntentRules.CART.matcher(normalized).matches()) {
            return new IntentClassification(IntentType.CART_OP, 0.7d, "fallback");
        }
        boolean hasPriorCandidates = memory != null && !memory.lastTurnSpuRefs().isEmpty();
        if (hasPriorCandidates && IntentRules.REFINE.matcher(normalized).matches()) {
            return new IntentClassification(IntentType.REFINE, 0.7d, "fallback");
        }
        if (IntentRules.PRICE.matcher(normalized).matches()) {
            return new IntentClassification(IntentType.FILTER_BY_ATTR, 0.7d, "fallback");
        }
        if (IntentRules.RECOMMEND.matcher(normalized).matches()) {
            return new IntentClassification(IntentType.RECOMMEND_VAGUE, 0.65d, "fallback");
        }
        return fallback();
    }

    private IntentClassification fallback() {
        return new IntentClassification(IntentType.RECOMMEND_VAGUE, 0.5d, "fallback");
    }

    private String normalize(String message) {
        return message == null ? "" : message.trim().replaceAll("\\s+", " ");
    }
}
