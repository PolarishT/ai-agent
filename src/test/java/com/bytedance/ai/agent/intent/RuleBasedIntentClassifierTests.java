package com.bytedance.ai.agent.intent;

import com.bytedance.ai.agent.api.IntentType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedIntentClassifierTests {

    private final RuleBasedIntentClassifier classifier = new RuleBasedIntentClassifier();

    @Test
    void detectsOutOfScopeByRuleL1() {
        assertIntent("帮我写代码实现快排", IntentType.OUT_OF_SCOPE, 0.95d, "rule_l1");
        assertIntent("讲笑话", IntentType.OUT_OF_SCOPE, 0.95d, "rule_l1");
        assertIntent("今天股票新闻怎么样", IntentType.OUT_OF_SCOPE, 0.95d, "rule_l1");
    }

    @Test
    void detectsPriceFiltersByRuleL1() {
        assertIntent("推荐 300 元以下的双肩包", IntentType.FILTER_BY_ATTR, 0.9d, "rule_l1");
        assertIntent("低于 500 的蓝牙耳机", IntentType.FILTER_BY_ATTR, 0.9d, "rule_l1");
        assertIntent("500-1000 元的行李箱", IntentType.FILTER_BY_ATTR, 0.9d, "rule_l1");
        assertIntent("预算 200 块的鼠标", IntentType.FILTER_BY_ATTR, 0.9d, "rule_l1");
    }

    @Test
    void detectsRecommendationByRuleL2() {
        assertIntent("推荐适合油皮的洗面奶", IntentType.RECOMMEND_VAGUE, 0.85d, "rule_l2");
        assertIntent("帮我找通勤电脑包", IntentType.RECOMMEND_VAGUE, 0.85d, "rule_l2");
        assertIntent("有没有防晒霜", IntentType.RECOMMEND_VAGUE, 0.85d, "rule_l2");
    }

    @Test
    void fallsBackToVagueRecommendation() {
        assertIntent("通勤双肩包", IntentType.RECOMMEND_VAGUE, 0.5d, "fallback");
        assertIntent("", IntentType.RECOMMEND_VAGUE, 0.5d, "fallback");
        assertIntent(null, IntentType.RECOMMEND_VAGUE, 0.5d, "fallback");
    }

    @Test
    void outOfScopeWinsBeforeOtherRules() {
        assertIntent("帮我写代码找 300 元以下商品", IntentType.OUT_OF_SCOPE, 0.95d, "rule_l1");
    }

    private void assertIntent(String message, IntentType intent, double confidence, String source) {
        IntentClassification classification = classifier.classify(message);
        assertThat(classification.intent()).isEqualTo(intent);
        assertThat(classification.confidence()).isEqualTo(confidence);
        assertThat(classification.source()).isEqualTo(source);
    }
}
