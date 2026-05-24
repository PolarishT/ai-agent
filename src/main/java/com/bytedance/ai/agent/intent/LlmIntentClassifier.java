package com.bytedance.ai.agent.intent;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.memory.ConversationMemory;
import com.bytedance.ai.shared.support.RagJsonCodec;
import com.bytedance.ai.shared.support.RagLogHelper;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * LLM-first intent classifier.
 *
 * <p>W1 的中文关键词正则只保留在 {@link RuleBasedIntentClassifier} 里做离线 / 异常兜底；
 * 线上主路径由模型根据消息和会话记忆输出结构化 intent。
 */
@Component
public class LlmIntentClassifier implements IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(LlmIntentClassifier.class);
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");

    private static final String SYSTEM_PROMPT = """
            你是电商导购 Agent 的意图分类器。只输出 JSON 对象，禁止解释、markdown、额外文本。

            可选 intent：
            - RECOMMEND_VAGUE：用户想买/找/推荐商品，但条件较宽泛。
            - FILTER_BY_ATTR：用户给出明确筛选条件，如预算、价格区间、品牌、品类、功能、材质、颜色、适用场景。
            - REFINE：用户基于上一轮候选继续收窄或替换，如“再便宜一点”“换个颜色”“这些里面要轻一点”。
              只有 hasPriorCandidates=true 时才能输出 REFINE。
            - COMPARE：用户要求比较两个或多个商品、问哪个好、差异、优缺点、性价比。
            - EXCLUDE：用户要求排除某些品牌/成分/风格/属性，如“不要酒精”“不要苹果”“不要黑色”。
            - SCENARIO_BUNDLE：用户要求围绕一个场景做组合/清单/套装推荐。
            - CART_OP：购物车、加购、删除、改数量、查看购物车、结算、下单相关。
            - IMAGE_SEARCH：用户明确提到图片/拍照/上传图/找同款/按图找货。
            - OUT_OF_SCOPE：非电商导购或购物流程请求，如写代码、新闻、天气、股票、翻译、闲聊。

            输出 JSON：
            {"intent":"RECOMMEND_VAGUE","confidence":0.0}

            few-shot：
            user=帮我找 300 元以下的通勤双肩包, hasPriorCandidates=false
              -> {"intent":"FILTER_BY_ATTR","confidence":0.92}
            user=这两款哪个更适合油皮, hasPriorCandidates=true
              -> {"intent":"COMPARE","confidence":0.91}
            user=把刚才那款加到购物车, hasPriorCandidates=true
              -> {"intent":"CART_OP","confidence":0.96}
            user=确认下单, hasPriorCandidates=true
              -> {"intent":"CART_OP","confidence":0.96}
            user=不要日系品牌，推荐防晒, hasPriorCandidates=false
              -> {"intent":"EXCLUDE","confidence":0.9}
            user=这些里面再便宜一点, hasPriorCandidates=true
              -> {"intent":"REFINE","confidence":0.9}
            user=这些里面再便宜一点, hasPriorCandidates=false
              -> {"intent":"FILTER_BY_ATTR","confidence":0.75}
            user=帮我写代码实现快排, hasPriorCandidates=false
              -> {"intent":"OUT_OF_SCOPE","confidence":0.97}
            """;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final RagJsonCodec jsonCodec;
    private final RuleBasedIntentClassifier fallbackClassifier = new RuleBasedIntentClassifier();

    public LlmIntentClassifier(ObjectProvider<ChatModel> chatModelProvider, RagJsonCodec jsonCodec) {
        this.chatModelProvider = chatModelProvider;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public IntentClassification classify(String message, ConversationMemory memory) {
        if (!StringUtils.hasText(message)) {
            return fallbackClassifier.classify(message, memory);
        }
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return fallbackClassifier.classify(message, memory);
        }
        try {
            String rawOutput = ChatClient.create(chatModel)
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt(message, memory))
                    .call()
                    .content();
            return normalize(parse(rawOutput), memory);
        } catch (RuntimeException exception) {
            log.debug("intent classification falls back: error={}", RagLogHelper.errorSummary(exception));
            return fallbackClassifier.classify(message, memory);
        }
    }

    IntentClassification parse(String rawOutput) {
        if (!StringUtils.hasText(rawOutput)) {
            throw new IllegalArgumentException("intent JSON 为空");
        }
        Map<String, Object> map = jsonCodec.readMap(extractJsonObject(rawOutput));
        IntentType intent = IntentType.valueOf(stringValue(map.get("intent")));
        double confidence = doubleValue(map.get("confidence"), 0.75d);
        return new IntentClassification(intent, clamp(confidence), "llm");
    }

    private IntentClassification normalize(IntentClassification classification, ConversationMemory memory) {
        boolean hasPriorCandidates = memory != null && !memory.lastTurnSpuRefs().isEmpty();
        if (classification.intent() == IntentType.REFINE && !hasPriorCandidates) {
            return new IntentClassification(IntentType.FILTER_BY_ATTR, Math.min(classification.confidence(), 0.75d), "llm");
        }
        return classification;
    }

    private String userPrompt(String message, ConversationMemory memory) {
        boolean hasPriorCandidates = memory != null && !memory.lastTurnSpuRefs().isEmpty();
        String lastIntent = memory != null && memory.lastTurnIntent().isPresent()
                ? memory.lastTurnIntent().get().name()
                : "";
        return "message=" + message
                + "\nhasPriorCandidates=" + hasPriorCandidates
                + "\nlastTurnSpuRefs=" + (memory == null ? "[]" : memory.lastTurnSpuRefs())
                + "\nlastTurnIntent=" + lastIntent;
    }

    private String extractJsonObject(String rawOutput) {
        String trimmed = rawOutput.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        java.util.regex.Matcher matcher = JSON_OBJECT_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }
        throw new IllegalArgumentException("未找到 intent JSON: " + rawOutput);
    }

    private String stringValue(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            throw new IllegalArgumentException("intent 缺失");
        }
        return String.valueOf(value).trim();
    }

    private double doubleValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            return Double.parseDouble(String.valueOf(value).trim());
        }
        return defaultValue;
    }

    private double clamp(double value) {
        if (value < 0d) {
            return 0d;
        }
        if (value > 1d) {
            return 1d;
        }
        return value;
    }
}
