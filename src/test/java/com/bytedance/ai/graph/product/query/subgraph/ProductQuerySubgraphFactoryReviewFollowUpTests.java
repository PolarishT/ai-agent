package com.bytedance.ai.graph.product.query.subgraph;

import com.bytedance.ai.graph.conversation.context.ConversationRuntimeContext;
import com.bytedance.ai.graph.product.query.ProductAttributesCondition;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.retrieval.ProductSearchHit;
import com.bytedance.ai.shared.metadata.RagChunkType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductQuerySubgraphFactoryReviewFollowUpTests {

    @Test
    void reviewFollowUpConditionClearsClarifyAndAvoidsProductPostFilterTerms() {
        ProductQueryCondition previous = condition(
                "三顿半咖啡",
                "三顿半咖啡",
                List.of("咖啡"),
                List.of("三顿半"),
                List.of("超即溶"),
                false,
                0.9d
        );
        ProductQueryCondition current = condition(
                "用户评价怎么样",
                "用户评价怎么样",
                List.of(),
                List.of(),
                List.of("太甜"),
                true,
                0.0d
        );

        ProductQueryCondition followUp = ProductQuerySubgraphFactory.reviewFollowUpCondition(
                current,
                previous,
                "用户评价怎么样"
        );

        assertThat(followUp.intent()).isEqualTo("REVIEW_QA");
        assertThat(followUp.needClarify()).isFalse();
        assertThat(followUp.missingSlots()).isEmpty();
        assertThat(followUp.confidence()).isGreaterThanOrEqualTo(0.8d);
        assertThat(followUp.brandTerms()).containsExactly("三顿半");
        assertThat(followUp.categoryTerms()).containsExactly("咖啡");
        assertThat(followUp.includeTerms()).isEmpty();
        assertThat(followUp.excludeTerms()).isEmpty();
        assertThat(followUp.mustHaveStock()).isFalse();
    }

    @Test
    void reviewFollowUpHitsPreferFocusAndDeduplicateCandidates() {
        ConversationRuntimeContext context = new ConversationRuntimeContext(
                1L,
                "user-1",
                "conversation-1",
                List.of(),
                new ConversationRuntimeContext.Focus(
                        10L,
                        "current",
                        1,
                        "23",
                        null,
                        "三顿半 数字星球系列",
                        Map.of()
                ),
                List.of(
                        new ConversationRuntimeContext.ProductCandidateItem(
                                11L,
                                1,
                                "23",
                                null,
                                "三顿半 数字星球系列",
                                null,
                                null,
                                null,
                                "23",
                                10,
                                Map.of()
                        ),
                        new ConversationRuntimeContext.ProductCandidateItem(
                                12L,
                                2,
                                "24",
                                null,
                                "另一个咖啡",
                                null,
                                null,
                                null,
                                "24",
                                8,
                                Map.of()
                        )
                ),
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                List.of()
        );

        List<ProductSearchHit> hits = ProductQuerySubgraphFactory.reviewFollowUpHits(context, 10);

        assertThat(hits).extracting(ProductSearchHit::productId).containsExactly(23L, 24L);
        assertThat(hits).allSatisfy(hit -> assertThat(hit.chunkType()).isEqualTo(RagChunkType.REVIEW.name()));
        assertThat(hits.get(0).metadata()).containsEntry("source", "previous_product_context");
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
    }

    private static ProductQueryCondition condition(
            String rawQuery,
            String normalizedQuery,
            List<String> categoryTerms,
            List<String> brandTerms,
            List<String> includeTerms,
            boolean needClarify,
            double confidence
    ) {
        return new ProductQueryCondition(
                rawQuery,
                normalizedQuery,
                "QUERY",
                "HYBRID",
                normalizedQuery,
                normalizedQuery,
                categoryTerms,
                List.of(),
                brandTerms,
                List.of(),
                includeTerms,
                List.of(),
                ProductAttributesCondition.empty(),
                null,
                null,
                null,
                "RELEVANCE",
                "INHERIT",
                List.of(),
                false,
                confidence,
                needClarify,
                needClarify ? List.of("intent") : List.of()
        );
    }
}
