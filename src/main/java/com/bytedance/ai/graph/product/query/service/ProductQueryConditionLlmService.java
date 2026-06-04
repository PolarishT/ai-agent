package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 用 LLM 把用户自然语言解析为 {@link ProductQueryCondition}。
 *
 * <p>失败时不抛异常，回退到 {@link ProductQueryCondition#empty(String)}（needClarify=true），
 * 上层 validator 会标记澄清状态。
 *
 * <p>业务 ID 软净化在两个时机执行：
 * <ul>
 *   <li>JSON 层：在 {@code treeToValue} 之前删 productId / skuId 等 key；</li>
 *   <li>值层：解析完成后剔除自由文本字段里的数字 ID token。</li>
 * </ul>
 */
@Service
public class ProductQueryConditionLlmService {

    private static final Logger log = LoggerFactory.getLogger(ProductQueryConditionLlmService.class);

    private static final String SYSTEM_PROMPT = """
            你是一个电商商品检索意图解析助手。读完用户消息（含历史摘要），仅输出一个 JSON 对象，
            字段固定如下：
            {
              "rawQuery": "...",                  // 原文，不要改写
              "normalizedQuery": "...",           // 去口语化的归一查询
              "intent": "QUERY | REFINE | COMPARE | RESET",
              "queryMode": "KEYWORD_ONLY | SEMANTIC_ONLY | HYBRID",
              "keywordQuery": "...",              // 关键词检索分支用
              "semanticQuery": "...",             // 语义检索分支用，可与 keywordQuery 相同
              "categoryTerms": ["..."],
              "excludeCategoryTerms": ["..."],     // 排除的品类（"不要苏打水"）
              "brandTerms": ["..."],
              "excludeBrandTerms": ["..."],
              "includeTerms": ["..."],
              "excludeTerms": ["..."],
              "attributes": {
                "color":    {"include": ["..."], "exclude": ["..."]},
                "size":     {"include": ["..."], "exclude": ["..."]},
                "material": {"include": ["..."], "exclude": ["..."]},
                "capacity": "500ml"
              },
              "priceMin": null,                   // 数字或 null（单位元）
              "priceMax": null,
              "mustHaveStock": null,              // true/false/null；用户明确要"有现货" 才设 true
              "sort": "RELEVANCE | PRICE_ASC | PRICE_DESC | RATING",
              "refineType": "INHERIT | OVERRIDE | APPEND | RESET",
              "comparisonTargets": [],             // 用户用 1-based 索引（基于上一轮候选）
              "comparisonTargetTexts": [],          // 用户直接点名的 2-3 个商品名 / 型号 / 外部编号
              "compareFocus": [],                   // 对比决策目标，如 性价比 / 通勤 / 补水 / 敏感肌
              "requestedDimensions": [],            // 用户显式要求展示的维度，如 续航 / 价格 / 用户评价
              "needComparison": false,
              "confidence": 0.0,                    // [0,1]
              "needClarify": false,
              "missingSlots": []
            }

            约束：
            - 严禁输出 productId / product_id / skuId / sku_id / cartItemId / cart_item_id / orderId / order_id 等业务 ID 字段；
            - 严禁输出数字商品 ID（>= 6 位的纯数字）到 includeTerms / excludeTerms / query；
            - 严禁输出多余 Markdown 代码块或解释，只输出 JSON；
            - 若用户说"再便宜点"用 INHERIT；"就要黑色"用 OVERRIDE；"不要黑色"用 APPEND；"重新搜"用 RESET；
            - 若用户说"对比前两个"或"对比第 1 和第 3 个"，把 1-based 索引列表填到 comparisonTargets，needComparison=true；
            - 若用户说"A 和 B 对比"、"A 和 B 哪个更适合通勤"，把 A/B 填到 comparisonTargetTexts，needComparison=true；
            - 若用户说"哪款更适合通勤/补水/敏感肌/视频剪辑/正式场合/性价比"，把这些决策目标填到 compareFocus；
            - 若用户说"按续航、价格、评价对比"，把这些显式维度填到 requestedDimensions；
            - 对比目标必须是 2-3 款；若用户要求对比超过 3 款，needClarify=true，missingSlots 包含 "comparisonTargets"；
            - 置信度低于 0.5 必须显式给 needClarify=true 并在 missingSlots 列出缺失字段名。
            """;

    private final ChatClient intentChatClient;
    private final ObjectMapper objectMapper;
    private final ProductQueryConditionSanitizer sanitizer;

    public ProductQueryConditionLlmService(
            @Qualifier("intentChatClient") ChatClient intentChatClient,
            ObjectMapper objectMapper,
            ProductQueryConditionSanitizer sanitizer
    ) {
        this.intentChatClient = intentChatClient;
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
    }

    public ProductQueryCondition parse(String userMessage, String conversationMemory, String previousConditionSummary) {
        if (!StringUtils.hasText(userMessage)) {
            return ProductQueryCondition.empty("");
        }
        String userPrompt = buildUserPrompt(userMessage, conversationMemory, previousConditionSummary);
        try {
            JsonNode root = intentChatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .entity(JsonNode.class);
            if (root == null) {
                log.warn("Product query condition LLM returned null structured output; falling back to clarify");
                return ProductQueryCondition.empty(userMessage);
            }
            JsonNode sanitized = sanitizer.sanitizeJson(root);
            ProductQueryCondition condition = objectMapper.treeToValue(sanitized, ProductQueryCondition.class);
            return sanitizer.sanitizeCondition(condition);
        } catch (Exception exception) {
            log.atWarn()
                    .addKeyValue("event.name", "product_query.condition.parse_failed")
                    .addKeyValue("event.outcome", "failure")
                    .setCause(exception)
                    .log("product query condition LLM parse failed; falling back to clarify");
            return ProductQueryCondition.empty(userMessage);
        }
    }

    private String buildUserPrompt(
            String userMessage,
            String conversationMemory,
            String previousConditionSummary
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户当前消息：\n").append(userMessage).append("\n\n");
        if (StringUtils.hasText(conversationMemory)) {
            builder.append("对话历史（按时间顺序）：\n").append(conversationMemory).append("\n\n");
        }
        if (StringUtils.hasText(previousConditionSummary)) {
            builder.append("上一轮商品检索 condition 摘要：\n")
                    .append(previousConditionSummary)
                    .append("\n\n");
        }
        builder.append("请输出唯一的 JSON 对象。");
        return builder.toString();
    }

    public static String summarizePrevious(ProductQueryCondition previous) {
        if (previous == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder("intent=").append(previous.intent())
                .append("; queryMode=").append(previous.queryMode());
        if (previous.priceMin() != null || previous.priceMax() != null) {
            builder.append("; price=[")
                    .append(previous.priceMin() == null ? "" : previous.priceMin())
                    .append(",")
                    .append(previous.priceMax() == null ? "" : previous.priceMax())
                    .append("]");
        }
        if (!previous.categoryTerms().isEmpty()) {
            builder.append("; category=").append(String.join("/", previous.categoryTerms()));
        }
        if (!previous.brandTerms().isEmpty()) {
            builder.append("; brand=").append(String.join("/", previous.brandTerms()));
        }
        if (!previous.includeTerms().isEmpty()) {
            builder.append("; include=").append(String.join("/", previous.includeTerms()));
        }
        if (!previous.excludeTerms().isEmpty()) {
            builder.append("; exclude=").append(String.join("/", previous.excludeTerms()));
        }
        appendAttribute(builder, "color", previous.attributes().color());
        appendAttribute(builder, "size", previous.attributes().size());
        appendAttribute(builder, "material", previous.attributes().material());
        if (StringUtils.hasText(previous.attributes().capacity())) {
            builder.append("; capacity=").append(previous.attributes().capacity());
        }
        return builder.toString();
    }

    private static void appendAttribute(StringBuilder builder, String name, com.bytedance.ai.graph.product.query.AttributeIncludeExclude attr) {
        if (attr == null) {
            return;
        }
        if (!attr.include().isEmpty()) {
            builder.append("; ").append(name).append(".include=").append(String.join("/", attr.include()));
        }
        if (!attr.exclude().isEmpty()) {
            builder.append("; ").append(name).append(".exclude=").append(String.join("/", attr.exclude()));
        }
    }

    // For tests that don't want to pull in ObjectProvider plumbing.
    @SuppressWarnings("unused")
    private List<String> dummyKeepImportsForJavaDoc() {
        return List.of();
    }
}
