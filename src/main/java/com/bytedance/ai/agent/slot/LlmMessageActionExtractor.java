package com.bytedance.ai.agent.slot;

import com.bytedance.ai.shared.support.RagJsonCodec;
import com.bytedance.ai.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认 LLM 实现。沿用 {@link NegationSlotExtractor} 的形态：
 * <pre>
 *   ObjectProvider&lt;ChatModel&gt; → ChatClient.create(...) → JSON 输出 → parse
 *   出错 / 模型缺席 → fallback() 正则兜底
 * </pre>
 *
 * <p>正则兜底保留了原 {@code AgentTurnService} 里的全部规则，外加：
 * <ul>
 *   <li>中文数字 {@code 一二两三四五六七八九十} + 量词的 quantity 识别；</li>
 *   <li>compare 维度字典扩展到 9 类（保湿/性价比/续航/屏幕/拍照/价格/重量/性能/容量/防水）。</li>
 * </ul>
 */
@Component
public class LlmMessageActionExtractor implements MessageActionExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmMessageActionExtractor.class);
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");

    // —— 正则兜底用预编译模式 —— //
    private static final Pattern CART_REMOVE = Pattern.compile("(删掉|删除|移除|拿掉)");
    private static final Pattern CART_UPDATE = Pattern.compile("(改成\\s*\\d+|数量|几件|更新)");
    private static final Pattern CART_PLACE_ORDER = Pattern.compile("(结算|下单|提交订单|确认下单|确认价格)");
    private static final Pattern CART_LIST = Pattern.compile("(查看|看看|看下|列表|购物车里|当前购物车)");

    private static final Pattern QUANTITY_DIGITS = Pattern.compile("(\\d+)\\s*(件|个|份|条|台|瓶|支)?");
    private static final Pattern QUANTITY_CN =
            Pattern.compile("(一|二|两|三|四|五|六|七|八|九|十)\\s*(件|个|份|条|台|瓶|支)");
    private static final Map<String, Integer> CN_DIGIT = Map.ofEntries(
            Map.entry("一", 1),
            Map.entry("二", 2),
            Map.entry("两", 2),
            Map.entry("三", 3),
            Map.entry("四", 4),
            Map.entry("五", 5),
            Map.entry("六", 6),
            Map.entry("七", 7),
            Map.entry("八", 8),
            Map.entry("九", 9),
            Map.entry("十", 10)
    );

    private static final Pattern PRICE_CONFIRM =
            Pattern.compile("(确认价格|价格变化也确认|确认下单|继续下单|接受价格|同意价格)");

    /** key=用户消息中的子串，value=归一化后的对比维度名（多个 key 可映射到同一维度）。 */
    private static final Map<String, String> COMPARE_ASPECT_DICT = new LinkedHashMap<>();

    static {
        COMPARE_ASPECT_DICT.put("保湿", "保湿");
        COMPARE_ASPECT_DICT.put("性价比", "性价比");
        COMPARE_ASPECT_DICT.put("续航", "续航");
        COMPARE_ASPECT_DICT.put("电池", "续航");
        COMPARE_ASPECT_DICT.put("屏幕", "屏幕");
        COMPARE_ASPECT_DICT.put("显示", "屏幕");
        COMPARE_ASPECT_DICT.put("拍照", "拍照");
        COMPARE_ASPECT_DICT.put("摄像", "拍照");
        COMPARE_ASPECT_DICT.put("价格", "价格");
        COMPARE_ASPECT_DICT.put("便宜", "价格");
        COMPARE_ASPECT_DICT.put("重量", "重量");
        COMPARE_ASPECT_DICT.put("轻便", "重量");
        COMPARE_ASPECT_DICT.put("性能", "性能");
        COMPARE_ASPECT_DICT.put("容量", "容量");
        COMPARE_ASPECT_DICT.put("防水", "防水");
    }

    private static final String SYSTEM_PROMPT = """
            你是电商导购的"动作槽抽取器"。只输出 JSON 对象，禁止解释 / markdown。

            目标字段（一次全部输出）：
            - cartAction: 购物车子动作。可选值：ADD（加入）/ LIST（查看 / 浏览购物车）/
              REMOVE（删除 / 移除）/ UPDATE_QTY（改数量 / 改成 N 件）/ PLACE_ORDER（结算 / 下单 / 提交订单）。
              非购物车场景一律输出 ADD（caller 会自行忽略）。
            - quantity: 用户提到的数量整数；未提及 → 1；上限 99。
            - priceChangeConfirmed: 用户是否在确认接受价格变化后下单（如"确认价格""接受新价格""继续下单"）。
            - compareAspects: 用户提到的对比维度归一化结果，可选：
              ["保湿","性价比","续航","屏幕","拍照","价格","重量","性能","容量","防水"]
              不在对比场景或没提到 → 空数组。

            JSON 形态：
            {"cartAction":"ADD","quantity":1,"priceChangeConfirmed":false,"compareAspects":[]}

            few-shot 示例：
            user: 把这个加到购物车，要 3 件
              -> {"cartAction":"ADD","quantity":3,"priceChangeConfirmed":false,"compareAspects":[]}
            user: 删掉购物车里的第二个
              -> {"cartAction":"REMOVE","quantity":1,"priceChangeConfirmed":false,"compareAspects":[]}
            user: 改成两件
              -> {"cartAction":"UPDATE_QTY","quantity":2,"priceChangeConfirmed":false,"compareAspects":[]}
            user: 看下购物车
              -> {"cartAction":"LIST","quantity":1,"priceChangeConfirmed":false,"compareAspects":[]}
            user: 确认价格，下单
              -> {"cartAction":"PLACE_ORDER","quantity":1,"priceChangeConfirmed":true,"compareAspects":[]}
            user: 帮我对比这两款手机的续航和拍照
              -> {"cartAction":"ADD","quantity":1,"priceChangeConfirmed":false,"compareAspects":["续航","拍照"]}
            user: 推荐补水面霜
              -> {"cartAction":"ADD","quantity":1,"priceChangeConfirmed":false,"compareAspects":[]}
            """;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final RagJsonCodec jsonCodec;

    public LlmMessageActionExtractor(ObjectProvider<ChatModel> chatModelProvider, RagJsonCodec jsonCodec) {
        this.chatModelProvider = chatModelProvider;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public MessageAction extract(String message) {
        if (!StringUtils.hasText(message)) {
            return MessageAction.defaults();
        }
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return fallback(message);
        }
        try {
            String rawOutput = ChatClient.create(chatModel)
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user("message=" + message)
                    .call()
                    .content();
            return parse(rawOutput);
        } catch (RuntimeException exception) {
            log.debug("message action extraction falls back to regex: error={}",
                    RagLogHelper.errorSummary(exception));
            return fallback(message);
        }
    }

    MessageAction parse(String rawOutput) {
        if (!StringUtils.hasText(rawOutput)) {
            return MessageAction.defaults();
        }
        Map<String, Object> map = jsonCodec.readMap(extractJsonObject(rawOutput));
        return new MessageAction(
                CartAction.fromName(stringValue(map.get("cartAction"))),
                intValue(map.get("quantity"), 1),
                booleanValue(map.get("priceChangeConfirmed")),
                stringList(map.get("compareAspects"))
        );
    }

    /**
     * 本地降级：把原 {@code AgentTurnService} 里的 4 段正则集中实现。
     * 与 LLM 路径输出严格同构，调用方无需区分。
     */
    MessageAction fallback(String message) {
        return new MessageAction(
                fallbackCartAction(message),
                fallbackQuantity(message),
                fallbackPriceConfirmed(message),
                fallbackCompareAspects(message)
        );
    }

    private CartAction fallbackCartAction(String message) {
        if (CART_REMOVE.matcher(message).find()) {
            return CartAction.REMOVE;
        }
        if (CART_UPDATE.matcher(message).find()) {
            return CartAction.UPDATE_QTY;
        }
        if (CART_PLACE_ORDER.matcher(message).find()) {
            return CartAction.PLACE_ORDER;
        }
        if (CART_LIST.matcher(message).find()) {
            return CartAction.LIST;
        }
        return CartAction.ADD;
    }

    private int fallbackQuantity(String message) {
        // 中文数字优先（"两件"/"三个"），避免被纯数字 regex 漏掉。
        Matcher cn = QUANTITY_CN.matcher(message);
        if (cn.find()) {
            return CN_DIGIT.getOrDefault(cn.group(1), 1);
        }
        Matcher digits = QUANTITY_DIGITS.matcher(message);
        if (digits.find()) {
            try {
                return Math.max(1, Integer.parseInt(digits.group(1)));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        if (message.contains("两")) {
            return 2;
        }
        return 1;
    }

    private boolean fallbackPriceConfirmed(String message) {
        return PRICE_CONFIRM.matcher(message).find();
    }

    private List<String> fallbackCompareAspects(String message) {
        LinkedHashSet<String> aspects = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : COMPARE_ASPECT_DICT.entrySet()) {
            if (message.contains(entry.getKey())) {
                aspects.add(entry.getValue());
            }
        }
        return List.copyOf(aspects);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(value).trim()));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (Object item : rawList) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (!text.isEmpty()) {
                normalized.add(text);
            }
        }
        return List.copyOf(normalized);
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
        throw new IllegalArgumentException("message action 输出未包含 JSON 对象");
    }
}
