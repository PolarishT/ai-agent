package com.bytedance.ai.graph.product.retrieval.filter;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductQueryIntent;
import com.bytedance.ai.shared.metadata.RagChunkType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 把 {@link ProductQueryIntent} 翻译为 Milvus scalar filter expression，只下推
 * {@code metadata["chunkType"]} 一个维度。
 *
 * <h2>为何只下推 chunkType</h2>
 * <p>当前 {@code RagMilvusVectorIndexer.toMetadata()} 写入的 metadata 字段只有：
 * {@code vectorId / documentId / productId / indexGeneration / sourceType / chunkType / chunkIndex}。
 * status / stock / price / category / brand 等业务字段并未写入 Milvus，下推这些 expression
 * 会直接命中"不存在的字段"导致 Milvus 端 zero hit。所以这个 builder 现阶段只生成 chunkType in [...]，
 * 其余硬过滤交给 PostgreSQL hard-filter（{@code ProductPostgresFilterBuilder}）与
 * hydrate 后的 {@code ProductCandidatePostFilter} 兜底。
 *
 * <h2>PREFERRED vs FALLBACK</h2>
 * <p>对 {@link ProductQueryIntent#PRODUCT_RECOMMEND} / {@link ProductQueryIntent#PRODUCT_SEARCH}：
 * <ul>
 *   <li>PREFERRED 限制为 {@code [PRODUCT_PROFILE, MARKETING]} —— 命中主商品 chunk + 营销卖点，质量最好；</li>
 *   <li>FALLBACK 放宽到 {@code [PRODUCT_PROFILE, MARKETING, FAQ_QUERY, FAQ_ANSWER]} ——
 *       让 FAQ 也参与召回，提高 recall，由 {@code ProductSemanticRagRetriever} 在 PREFERRED zero hit
 *       时切换。</li>
 * </ul>
 * 其他 intent 的 PREFERRED 已经足够宽，没有专门的 FALLBACK（统一走 default 全集）。
 *
 * <h2>命名约定</h2>
 * <p>spec 里出现的 {@code FAQ_QA} 与 {@code KNOWLEDGE} 是旧版 chunk_type 命名。当前 ingestion 把
 * FAQ 文档按 heading 切成 {@link RagChunkType#FAQ_QUERY} / {@link RagChunkType#FAQ_ANSWER} 两类，
 * 把 product knowledge 归一为 {@link RagChunkType#MARKETING}。因此 expression 里实际写的是
 * 当前枚举值，不是旧名字。
 */
@Component
public class MilvusScalarFilterBuilder {

    private static final String CHUNK_TYPE_FIELD = "chunkType";

    /**
     * 推荐场景的「首选」chunkType 过滤；让 Milvus 优先命中高质量证据。
     */
    public String buildPreferred(ProductQueryIntent intent) {
        return chunkTypeIn(preferredChunkTypes(intent));
    }

    /**
     * 推荐场景的「兜底」chunkType 过滤；首选命中为空时由 retriever 切换到此版本。
     */
    public String buildFallback(ProductQueryIntent intent) {
        return chunkTypeIn(fallbackChunkTypes(intent));
    }

    /**
     * 旧 API：返回 {@code null}，留作向后兼容（外部已有 caller 调用 build(condition)）。
     * 新代码请使用 {@link #buildPreferred(ProductQueryIntent)} / {@link #buildFallback(ProductQueryIntent)}。
     *
     * @deprecated 旧版本生成 status/stock/price/category/brand 多维度 scalar filter，
     *             但 Milvus metadata 并未写入这些字段，下推后无效甚至误命中 zero hit。
     */
    @Deprecated
    public String build(ProductQueryCondition condition) {
        return null;
    }

    private List<String> preferredChunkTypes(ProductQueryIntent intent) {
        ProductQueryIntent resolved = intent == null ? ProductQueryIntent.PRODUCT_SEARCH : intent;
        return switch (resolved) {
            // PREFERRED: 主商品 chunk + 营销卖点；命中质量优先
            case PRODUCT_RECOMMEND, PRODUCT_SEARCH ->
                    List.of(RagChunkType.PRODUCT_PROFILE.name(), RagChunkType.MARKETING.name());
            // PRODUCT_QA: FAQ 问答 + 主商品 chunk + 营销（MARKETING 当作 KNOWLEDGE 用，承载 USAGE_GUIDE / SELLING_POINTS）
            case PRODUCT_QA -> List.of(
                    RagChunkType.FAQ_QUERY.name(),
                    RagChunkType.FAQ_ANSWER.name(),
                    RagChunkType.PRODUCT_PROFILE.name(),
                    RagChunkType.MARKETING.name()
            );
            // REVIEW 场景：评价 + 主商品 chunk（避免只看评论无法挂回商品）
            case REVIEW_QA ->
                    List.of(RagChunkType.REVIEW.name(), RagChunkType.PRODUCT_PROFILE.name());
            // COMPARE：所有有意义的 evidence 都要
            case PRODUCT_COMPARE -> List.of(
                    RagChunkType.PRODUCT_PROFILE.name(),
                    RagChunkType.FAQ_QUERY.name(),
                    RagChunkType.FAQ_ANSWER.name(),
                    RagChunkType.REVIEW.name(),
                    RagChunkType.MARKETING.name()
            );
            // INVENTORY / PRICE / FAQ_QA：默认全集
            case INVENTORY_CHECK, PRICE_QA, FAQ_QA -> allChunkTypes();
        };
    }

    private List<String> fallbackChunkTypes(ProductQueryIntent intent) {
        ProductQueryIntent resolved = intent == null ? ProductQueryIntent.PRODUCT_SEARCH : intent;
        return switch (resolved) {
            // 推荐 / 搜索的 FALLBACK：把 FAQ 也放进来，提高 recall
            case PRODUCT_RECOMMEND, PRODUCT_SEARCH -> List.of(
                    RagChunkType.PRODUCT_PROFILE.name(),
                    RagChunkType.MARKETING.name(),
                    RagChunkType.FAQ_QUERY.name(),
                    RagChunkType.FAQ_ANSWER.name()
            );
            // 其他场景 PREFERRED 已经偏宽，FALLBACK 直接退化到全集
            default -> allChunkTypes();
        };
    }

    private List<String> allChunkTypes() {
        return List.of(
                RagChunkType.PRODUCT_PROFILE.name(),
                RagChunkType.MARKETING.name(),
                RagChunkType.FAQ_QUERY.name(),
                RagChunkType.FAQ_ANSWER.name(),
                RagChunkType.REVIEW.name()
        );
    }

    private String chunkTypeIn(List<String> chunkTypes) {
        if (chunkTypes == null || chunkTypes.isEmpty()) {
            return null;
        }
        // 去重保序，避免 spec 误把同一类型写两次
        Set<String> distinct = new LinkedHashSet<>(chunkTypes);
        String joined = distinct.stream()
                .map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(", "));
        return CHUNK_TYPE_FIELD + " in [" + joined + "]";
    }
}
