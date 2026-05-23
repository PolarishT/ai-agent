package com.bytedance.ai.agent.intent;

import com.bytedance.ai.agent.api.IntentType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * W1 规则路由：OOS / 价格过滤 / 推荐动词 / RECOMMEND_VAGUE 兜底。
 */
@Component
public class RuleBasedIntentClassifier implements IntentClassifier {

    @Override
    public IntentClassification classify(String message) {
        String normalized = normalize(message);
        if (!StringUtils.hasText(normalized)) {
            return fallback();
        }
        if (IntentRules.OUT_OF_SCOPE.matcher(normalized).matches()) {
            return new IntentClassification(IntentType.OUT_OF_SCOPE, 0.95d, "rule_l1");
        }
        if (IntentRules.PRICE.matcher(normalized).matches()) {
            return new IntentClassification(IntentType.FILTER_BY_ATTR, 0.9d, "rule_l1");
        }
        if (IntentRules.RECOMMEND.matcher(normalized).matches()) {
            return new IntentClassification(IntentType.RECOMMEND_VAGUE, 0.85d, "rule_l2");
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
