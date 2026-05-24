package com.bytedance.ai.retrieval.service;

import com.bytedance.ai.shared.metadata.RagSearchFilter;
import com.bytedance.ai.shared.support.RagJsonCodec;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 将可下推的检索过滤条件编译为 Milvus 原生过滤表达式。
 *
 * <p>W2 起追加反选支持：
 * <ul>
 *   <li>{@code mustNotTags}：{@code !json_contains_any(metadata["documentTags"], [...])}</li>
 *   <li>{@code mustNotBrands}：{@code metadata["brand"] not in [...]}</li>
 *   <li>{@code mustNotIngredients}：{@code !(metadata["content"] LIKE "%X%")}（在召回侧粗过滤，
 *       命中精度交给 NegationRerankFilter 兜底）</li>
 * </ul>
 */
@Component
public class RagMilvusNativeExpressionBuilder {

    private final RagJsonCodec jsonCodec;

    public RagMilvusNativeExpressionBuilder(RagJsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    /**
     * 构造 Milvus 原生过滤表达式。
     *
     * <p>返回 {@code null} 表示"无过滤条件"，调用方在 {@code SearchRequest} 上不应设置该参数；
     * 返回非空时各表达式以 {@code AND} 串联，保证"全部正向条件命中 AND 全部反向条件未命中"。
     */
    public String build(RagSearchFilter filter) {
        if (filter == null || filter.isEmpty()) {
            return null;
        }

        java.util.ArrayList<String> expressions = new java.util.ArrayList<>();
        if (StringUtils.hasText(filter.sourceUriPrefix())) {
            expressions.add(MilvusVectorStore.METADATA_FIELD_NAME
                    + "[\"sourceUri\"] LIKE \""
                    + escapeLikeLiteral(filter.sourceUriPrefix())
                    + "%\"");
        }
        if (filter.tags() != null && !filter.tags().isEmpty()) {
            expressions.add("json_contains_all("
                    + MilvusVectorStore.METADATA_FIELD_NAME
                    + "[\"documentTags\"], "
                    + toJsonArray(filter.tags())
                    + ")");
        }
        if (StringUtils.hasText(filter.headingPathContains())) {
            expressions.add(MilvusVectorStore.METADATA_FIELD_NAME
                    + "[\"headingPathText\"] LIKE \"%"
                    + escapeLikeLiteral(normalizeLowerCase(filter.headingPathContains()))
                    + "%\"");
        }
        if (!filter.mustNotTags().isEmpty()) {
            expressions.add("not json_contains_any("
                    + MilvusVectorStore.METADATA_FIELD_NAME
                    + "[\"documentTags\"], "
                    + toJsonArray(filter.mustNotTags())
                    + ")");
        }
        if (!filter.mustNotBrands().isEmpty()) {
            expressions.add(MilvusVectorStore.METADATA_FIELD_NAME
                    + "[\"brand\"] not in "
                    + toJsonArray(filter.mustNotBrands()));
        }
        if (!filter.mustNotIngredients().isEmpty()) {
            // chunk 正文 LIKE 的负向过滤；每个成分一条 NOT LIKE，AND 串联。
            for (String ingredient : filter.mustNotIngredients()) {
                expressions.add("not "
                        + MilvusVectorStore.METADATA_FIELD_NAME
                        + "[\"contentSummary\"] LIKE \"%"
                        + escapeLikeLiteral(ingredient)
                        + "%\"");
            }
        }

        if (expressions.isEmpty()) {
            return null;
        }
        return String.join(" AND ", expressions);
    }

    private String escapeLikeLiteral(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("%", "\\\\%")
                .replace("_", "\\\\_");
    }

    private String normalizeLowerCase(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String toJsonArray(List<String> values) {
        try {
            return jsonCodec.write(values);
        } catch (Exception exception) {
            throw new IllegalStateException("构建 Milvus tags 过滤表达式失败", exception);
        }
    }
}
