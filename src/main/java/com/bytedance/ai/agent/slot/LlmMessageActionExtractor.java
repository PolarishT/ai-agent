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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认 LLM 实现：通过 {@link ChatClient} 一次性抽取 cartAction / quantity /
 * priceChangeConfirmed / compareAspects 四个字段。
 *
 * <p>没有正则兜底：
 * <ul>
 *   <li>未注入 ChatModel（离线 / 单测）→ 返回 {@link MessageAction#defaults()}；</li>
 *   <li>LLM 抛异常 / 输出无法解析 → 记 warn 日志，同样返回 defaults；</li>
 * </ul>
 * defaults 的语义即"未识别到任何动作意图，按默认 ADD/1/false/[] 处理"，
 * 由上游 {@code AgentTurnService} 自行决定是否调用相应工具。
 */
@Component
public class LlmMessageActionExtractor implements MessageActionExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmMessageActionExtractor.class);
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");

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
            return MessageAction.defaults();
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
            log.warn("message action extraction failed, using defaults: error={}",
                    RagLogHelper.errorSummary(exception));
            return MessageAction.defaults();
        }
    }

    MessageAction parse(String rawOutput) {
        if (!StringUtils.hasText(rawOutput)) {
            return MessageAction.defaults();
        }
        try {
            Map<String, Object> map = jsonCodec.readMap(extractJsonObject(rawOutput));
            return new MessageAction(
                    CartAction.fromName(stringValue(map.get("cartAction"))),
                    intValue(map.get("quantity"), 1),
                    booleanValue(map.get("priceChangeConfirmed")),
                    stringList(map.get("compareAspects"))
            );
        } catch (RuntimeException exception) {
            log.warn("message action JSON parse failed, using defaults: error={}",
                    RagLogHelper.errorSummary(exception));
            return MessageAction.defaults();
        }
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
