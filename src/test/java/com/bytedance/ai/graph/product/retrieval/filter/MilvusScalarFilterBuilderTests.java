package com.bytedance.ai.graph.product.retrieval.filter;

import com.bytedance.ai.graph.product.query.ProductQueryIntent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * MilvusScalarFilterBuilder 单测。
 *
 * <p>这一层只下推 chunkType，不下推 category/subCategory/status/stock/price/brand —— 因为
 * 当前 Milvus metadata 里不存在那些字段（详见 {@code RagMilvusVectorIndexer.toMetadata}）。
 */
class MilvusScalarFilterBuilderTests {

    private final MilvusScalarFilterBuilder builder = new MilvusScalarFilterBuilder();

    @Test
    void productRecommendPreferredEmitsProfileAndMarketing() {
        String expr = builder.buildPreferred(ProductQueryIntent.PRODUCT_RECOMMEND);
        assertThat(expr).isEqualTo("chunkType in [\"PRODUCT_PROFILE\", \"MARKETING\"]");
    }

    @Test
    void productRecommendFallbackBroadensToFaq() {
        String expr = builder.buildFallback(ProductQueryIntent.PRODUCT_RECOMMEND);
        assertThat(expr).isEqualTo(
                "chunkType in [\"PRODUCT_PROFILE\", \"MARKETING\", \"FAQ_QUERY\", \"FAQ_ANSWER\"]");
    }

    @Test
    void productSearchPreferredAndFallbackMatchRecommend() {
        // spec：PRODUCT_RECOMMEND 与 PRODUCT_SEARCH (== PRODUCT_QUERY) 共享同样的两阶段规则
        assertThat(builder.buildPreferred(ProductQueryIntent.PRODUCT_SEARCH))
                .isEqualTo(builder.buildPreferred(ProductQueryIntent.PRODUCT_RECOMMEND));
        assertThat(builder.buildFallback(ProductQueryIntent.PRODUCT_SEARCH))
                .isEqualTo(builder.buildFallback(ProductQueryIntent.PRODUCT_RECOMMEND));
    }

    @Test
    void productQaIncludesFaqProfileAndKnowledgeAsMarketing() {
        // spec 的 KNOWLEDGE 当前归一为 MARKETING；FAQ_QA 当前拆成 FAQ_QUERY + FAQ_ANSWER
        String expr = builder.buildPreferred(ProductQueryIntent.PRODUCT_QA);
        assertThat(expr).isEqualTo(
                "chunkType in [\"FAQ_QUERY\", \"FAQ_ANSWER\", \"PRODUCT_PROFILE\", \"MARKETING\"]");
    }

    @Test
    void reviewQaIncludesReviewAndProfile() {
        // spec 的 REVIEW_SUMMARY 对应 ProductQueryIntent.REVIEW_QA
        String expr = builder.buildPreferred(ProductQueryIntent.REVIEW_QA);
        assertThat(expr).isEqualTo("chunkType in [\"REVIEW\", \"PRODUCT_PROFILE\"]");
    }

    @Test
    void productCompareCoversAllEvidenceTypes() {
        String expr = builder.buildPreferred(ProductQueryIntent.PRODUCT_COMPARE);
        assertThat(expr).isEqualTo(
                "chunkType in [\"PRODUCT_PROFILE\", \"FAQ_QUERY\", \"FAQ_ANSWER\", \"REVIEW\", \"MARKETING\"]");
    }

    @Test
    void defaultIntentEmitsFullChunkTypeSet() {
        // INVENTORY_CHECK / PRICE_QA / FAQ_QA 都走默认全集
        String expected = "chunkType in [\"PRODUCT_PROFILE\", \"MARKETING\", \"FAQ_QUERY\", \"FAQ_ANSWER\", \"REVIEW\"]";
        assertThat(builder.buildPreferred(ProductQueryIntent.INVENTORY_CHECK)).isEqualTo(expected);
        assertThat(builder.buildPreferred(ProductQueryIntent.PRICE_QA)).isEqualTo(expected);
        assertThat(builder.buildPreferred(ProductQueryIntent.FAQ_QA)).isEqualTo(expected);
    }

    @Test
    void fallbackForNonRecommendIntentDefaultsToFullSet() {
        String fullSet = "chunkType in [\"PRODUCT_PROFILE\", \"MARKETING\", \"FAQ_QUERY\", \"FAQ_ANSWER\", \"REVIEW\"]";
        assertThat(builder.buildFallback(ProductQueryIntent.PRODUCT_QA)).isEqualTo(fullSet);
        assertThat(builder.buildFallback(ProductQueryIntent.REVIEW_QA)).isEqualTo(fullSet);
        assertThat(builder.buildFallback(ProductQueryIntent.PRODUCT_COMPARE)).isEqualTo(fullSet);
    }

    @Test
    void nullIntentFallsBackToProductSearch() {
        assertThat(builder.buildPreferred(null))
                .isEqualTo(builder.buildPreferred(ProductQueryIntent.PRODUCT_SEARCH));
        assertThat(builder.buildFallback(null))
                .isEqualTo(builder.buildFallback(ProductQueryIntent.PRODUCT_SEARCH));
    }

    @Test
    void exprNeverEmitsRawCategoryOrStockBecauseMilvusMetadataLacksThem() {
        for (ProductQueryIntent intent : ProductQueryIntent.values()) {
            String preferred = builder.buildPreferred(intent);
            String fallback = builder.buildFallback(intent);
            assertThat(preferred).doesNotContain("category", "sub_category", "stock", "price", "brand", "status");
            assertThat(fallback).doesNotContain("category", "sub_category", "stock", "price", "brand", "status");
        }
    }

    @Test
    void deprecatedBuildReturnsNull() {
        // 旧 API 已废弃，不再生成 status/stock/price 表达式
        assertThat(builder.build(null)).isNull();
    }
}
