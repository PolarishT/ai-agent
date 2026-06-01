package com.bytedance.ai.graph.cartmanage.subgraph.support;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.conversation.ConversationMessage;
import com.bytedance.ai.graph.orchestration.GuideGraphStateKeys;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 购物车子图状态读写辅助工具。
 */
public final class CartGraphStateSupport {

    private static final List<Pattern> PRICE_PATTERNS = List.of(
            Pattern.compile("(?:商品)?价格\\s*(?:为|是|=|：|:)?\\s*[¥￥]?\\s*(\\d+(?:\\.\\d{1,2})?)"),
            Pattern.compile("(?:预算|价位)\\s*(?:为|是|=|：|:)?\\s*[¥￥]?\\s*(\\d+(?:\\.\\d{1,2})?)"),
            Pattern.compile("[¥￥]\\s*(\\d+(?:\\.\\d{1,2})?)"),
            Pattern.compile("(\\d+(?:\\.\\d{1,2})?)\\s*元\\s*(?:的那个|那个|这款|的)?")
    );

    private CartGraphStateSupport() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readIntentSlots(OverAllState state) {
        return state.value(GuideGraphStateKeys.INTENT_SLOTS)
                .filter(v -> v instanceof Map)
                .map(v -> (Map<String, Object>) v)
                .orElse(Map.of());
    }

    public static String conversationMemory(OverAllState state) {
        List<?> recentMessages = state.value(GuideGraphStateKeys.RECENT_MESSAGES, List.of());
        if (recentMessages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Object value : recentMessages) {
            if (value instanceof ConversationMessage message) {
                builder.append(message.role()).append(": ").append(message.content()).append('\n');
            }
        }
        return builder.toString().trim();
    }

    public static String requiredString(OverAllState state, String key) {
        return state.value(key)
                .map(Object::toString)
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Missing state key: " + key));
    }

    public static String asString(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    public static Integer asInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static BigDecimal asBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (StringUtils.hasText(v)) return v;
        }
        return null;
    }

    @SafeVarargs
    public static <T> T firstNonNull(T... values) {
        if (values == null) return null;
        for (T value : values) {
            if (value != null) return value;
        }
        return null;
    }

    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public static String normalizeMatchText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    public static BigDecimal extractExpectedPrice(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        for (Pattern pattern : PRICE_PATTERNS) {
            Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {
                try {
                    return new BigDecimal(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
