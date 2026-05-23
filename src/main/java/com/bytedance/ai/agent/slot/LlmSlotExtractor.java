package com.bytedance.ai.agent.slot;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.shared.support.RagJsonCodec;
import com.bytedance.ai.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slot 抽取器：优先使用 ChatModel 输出 JSON，失败时只用正则抽 priceRange。
 */
@Component
public class LlmSlotExtractor implements SlotExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmSlotExtractor.class);
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");
    private static final Pattern PRICE_RANGE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*[-~到至]\\s*(\\d+(?:\\.\\d+)?)\\s*(元|块)?");
    private static final Pattern PRICE_BELOW_SUFFIX = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(元|块)?\\s*(以下|以内|内|之内)");
    private static final Pattern PRICE_BELOW_PREFIX = Pattern.compile("(低于|小于|少于|不超过|预算)\\s*(\\d+(?:\\.\\d+)?)\\s*(元|块)?");

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final RagJsonCodec jsonCodec;

    public LlmSlotExtractor(ObjectProvider<ChatModel> chatModelProvider, RagJsonCodec jsonCodec) {
        this.chatModelProvider = chatModelProvider;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Slot extract(String message, IntentType intent) {
        if (!StringUtils.hasText(message) || intent == IntentType.OUT_OF_SCOPE) {
            return Slot.empty();
        }
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return fallback(message);
        }
        try {
            String rawOutput = ChatClient.create(chatModel)
                    .prompt()
                    .system("""
                            你是电商导购的槽位抽取器。只输出 JSON 对象，不要解释。
                            字段：must: string[], priceRange: {min:number|null,max:number|null}|null,
                            categoryHint: string|null, brands: string[]。
                            不确定的字段输出空数组或 null。
                            """)
                    .user("intent=" + intent + "\nmessage=" + message)
                    .call()
                    .content();
            return parse(rawOutput);
        } catch (RuntimeException exception) {
            log.debug("slot extraction falls back to regex: error={}", RagLogHelper.errorSummary(exception));
            return fallback(message);
        }
    }

    Slot parse(String rawOutput) {
        if (!StringUtils.hasText(rawOutput)) {
            throw new IllegalArgumentException("slot JSON 为空");
        }
        Map<String, Object> map = jsonCodec.readMap(extractJsonObject(rawOutput));
        return new Slot(
                stringList(map.get("must")),
                List.of(),
                priceRange(map.get("priceRange")),
                stringValue(map.get("categoryHint")),
                stringList(map.get("brands")),
                null
        );
    }

    Slot fallback(String message) {
        return new Slot(List.of(), List.of(), extractPriceRange(message), null, List.of(), null);
    }

    private Slot.PriceRange extractPriceRange(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        Matcher rangeMatcher = PRICE_RANGE.matcher(message);
        if (rangeMatcher.find()) {
            BigDecimal left = decimal(rangeMatcher.group(1));
            BigDecimal right = decimal(rangeMatcher.group(2));
            return left.compareTo(right) <= 0
                    ? new Slot.PriceRange(left, right)
                    : new Slot.PriceRange(right, left);
        }
        Matcher suffixMatcher = PRICE_BELOW_SUFFIX.matcher(message);
        if (suffixMatcher.find()) {
            return new Slot.PriceRange(null, decimal(suffixMatcher.group(1)));
        }
        Matcher prefixMatcher = PRICE_BELOW_PREFIX.matcher(message);
        if (prefixMatcher.find()) {
            return new Slot.PriceRange(null, decimal(prefixMatcher.group(2)));
        }
        return null;
    }

    private Slot.PriceRange priceRange(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return null;
        }
        return new Slot.PriceRange(decimalObject(rawMap.get("min")), decimalObject(rawMap.get("max")));
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream()
                .filter(item -> item != null && StringUtils.hasText(String.valueOf(item)))
                .map(item -> String.valueOf(item).trim())
                .toList();
    }

    private String stringValue(Object value) {
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? null : String.valueOf(value).trim();
    }

    private BigDecimal decimalObject(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return decimal(String.valueOf(value));
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value.trim());
    }

    private String extractJsonObject(String rawOutput) {
        String trimmed = rawOutput.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        Matcher matcher = JSON_OBJECT_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }
        throw new IllegalArgumentException("slot 输出未包含 JSON 对象");
    }
}
