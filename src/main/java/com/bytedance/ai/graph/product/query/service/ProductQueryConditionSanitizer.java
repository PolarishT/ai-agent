package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.AttributeIncludeExclude;
import com.bytedance.ai.graph.product.query.ProductAttributesCondition;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 业务 ID 软净化器。LLM 不应在 {@link ProductQueryCondition} 输出里塞 productId / skuId 等
 * 后台 ID 字段；万一塞了，这里负责删除而不是阻断链路。
 *
 * <p>两层净化：
 * <ol>
 *   <li>JSON 层：拿到 LLM 原始 JSON 后，递归删除任何 key 命中黑名单的字段。</li>
 *   <li>值层：对自由文本字段（includeTerms / excludeTerms / keywordQuery / semanticQuery）
 *       用正则剥掉看起来像数字 ID 的 token（连续 6 位以上数字）。</li>
 * </ol>
 *
 * <p>每次净化命中均累加 {@code rag_product_query_llm_leak_total{field=...}} 指标，便于观察。
 */
@Component
public class ProductQueryConditionSanitizer {

    private static final Logger log = LoggerFactory.getLogger(ProductQueryConditionSanitizer.class);

    private static final Set<String> ID_KEYS = Set.of(
            "productid", "product_id",
            "skuid", "sku_id",
            "cartitemid", "cart_item_id",
            "orderid", "order_id"
    );

    private static final Pattern ID_LIKE_NUMERIC = Pattern.compile("\\b\\d{6,}\\b");
    private static final String LEAK_COUNTER = "rag_product_query_llm_leak_total";

    private final ObjectMapper objectMapper;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public ProductQueryConditionSanitizer(
            ObjectMapper objectMapper,
            ObjectProvider<MeterRegistry> meterRegistryProvider
    ) {
        this.objectMapper = objectMapper;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /**
     * 清洗 LLM 原始 JSON：删除任何 key 命中黑名单的字段，记 warn + metric，
     * 返回净化后的 JsonNode 供后续 {@code treeToValue} 解析。
     */
    public JsonNode sanitizeJson(JsonNode root) {
        if (root == null) {
            return null;
        }
        stripBlacklistedKeys(root);
        return root;
    }

    /**
     * 清洗已解析的 condition：对自由文本字段剔除 ID-shaped numeric tokens。
     * 保持 condition 其它字段不变；命中即记 warn + metric。
     */
    public ProductQueryCondition sanitizeCondition(ProductQueryCondition condition) {
        if (condition == null) {
            return null;
        }
        String keywordQuery = stripNumericTokens(condition.keywordQuery(), "keywordQuery");
        String semanticQuery = stripNumericTokens(condition.semanticQuery(), "semanticQuery");
        List<String> includeTerms = stripNumericTokens(condition.includeTerms(), "includeTerms");
        List<String> excludeTerms = stripNumericTokens(condition.excludeTerms(), "excludeTerms");
        List<String> comparisonTargetTexts = stripNumericTokens(
                condition.comparisonTargetTexts(), "comparisonTargetTexts"
        );
        List<String> compareFocus = stripNumericTokens(condition.compareFocus(), "compareFocus");
        List<String> requestedDimensions = stripNumericTokens(
                condition.requestedDimensions(), "requestedDimensions"
        );
        ProductAttributesCondition attributes = sanitizeAttributes(condition.attributes());
        return new ProductQueryCondition(
                condition.rawQuery(),
                condition.normalizedQuery(),
                condition.intent(),
                condition.queryMode(),
                keywordQuery,
                semanticQuery,
                condition.categoryTerms(),
                condition.excludeCategoryTerms(),
                condition.brandTerms(),
                condition.excludeBrandTerms(),
                includeTerms,
                excludeTerms,
                attributes,
                condition.priceMin(),
                condition.priceMax(),
                condition.mustHaveStock(),
                condition.sort(),
                condition.refineType(),
                condition.comparisonTargets(),
                comparisonTargetTexts,
                compareFocus,
                requestedDimensions,
                condition.needComparison(),
                condition.confidence(),
                condition.needClarify(),
                condition.missingSlots()
        );
    }

    private void stripBlacklistedKeys(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            List<String> toRemove = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> it = objectNode.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String key = entry.getKey();
                if (ID_KEYS.contains(key.toLowerCase())) {
                    toRemove.add(key);
                } else {
                    stripBlacklistedKeys(entry.getValue());
                }
            }
            for (String key : toRemove) {
                objectNode.remove(key);
                recordLeak("json_key:" + key);
                log.warn("Product query condition LLM leaked business ID field; stripped: field={}", key);
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                stripBlacklistedKeys(item);
            }
        }
    }

    private ProductAttributesCondition sanitizeAttributes(ProductAttributesCondition attributes) {
        if (attributes == null) {
            return ProductAttributesCondition.empty();
        }
        return new ProductAttributesCondition(
                sanitizeIncludeExclude(attributes.color(), "attributes.color"),
                sanitizeIncludeExclude(attributes.size(), "attributes.size"),
                sanitizeIncludeExclude(attributes.material(), "attributes.material"),
                stripNumericTokens(attributes.capacity(), "attributes.capacity")
        );
    }

    private AttributeIncludeExclude sanitizeIncludeExclude(AttributeIncludeExclude value, String fieldPath) {
        if (value == null) {
            return AttributeIncludeExclude.empty();
        }
        return new AttributeIncludeExclude(
                stripNumericTokens(value.include(), fieldPath + ".include"),
                stripNumericTokens(value.exclude(), fieldPath + ".exclude")
        );
    }

    private String stripNumericTokens(String value, String fieldPath) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (ID_LIKE_NUMERIC.matcher(value).find()) {
            String stripped = ID_LIKE_NUMERIC.matcher(value).replaceAll(" ").trim();
            recordLeak("value:" + fieldPath);
            log.warn("Product query condition LLM leaked ID-like numeric token; stripped: field={}", fieldPath);
            return stripped;
        }
        return value;
    }

    private List<String> stripNumericTokens(List<String> values, String fieldPath) {
        if (values == null || values.isEmpty()) {
            return values == null ? List.of() : values;
        }
        boolean changed = false;
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) {
            if (value != null && ID_LIKE_NUMERIC.matcher(value).find()) {
                String stripped = ID_LIKE_NUMERIC.matcher(value).replaceAll(" ").trim();
                if (stripped.isEmpty()) {
                    recordLeak("value:" + fieldPath);
                    log.warn("Product query condition LLM leaked ID-like numeric token; dropped: field={}", fieldPath);
                    changed = true;
                    continue;
                }
                recordLeak("value:" + fieldPath);
                log.warn("Product query condition LLM leaked ID-like numeric token; stripped: field={}", fieldPath);
                result.add(stripped);
                changed = true;
            } else if (value != null) {
                result.add(value);
            }
        }
        return changed ? List.copyOf(result) : values;
    }

    private void recordLeak(String fieldTag) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        Counter.builder(LEAK_COUNTER)
                .tag("field", fieldTag)
                .register(registry)
                .increment();
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }
}
