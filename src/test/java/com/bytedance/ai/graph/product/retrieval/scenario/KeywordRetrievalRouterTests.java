package com.bytedance.ai.graph.product.retrieval.scenario;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.retrieval.ProductSearchHit;
import com.bytedance.ai.shared.metadata.RagChunkType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeywordRetrievalRouterTests {

    private ProductProfileSearcher profileSearcher;
    private MarketingSearcher marketingSearcher;
    private FaqSearcher faqSearcher;
    private ReviewSearcher reviewSearcher;
    private KeywordRetrievalRouter router;

    @BeforeEach
    void setUp() {
        profileSearcher = mock(ProductProfileSearcher.class);
        marketingSearcher = mock(MarketingSearcher.class);
        faqSearcher = mock(FaqSearcher.class);
        reviewSearcher = mock(ReviewSearcher.class);
        router = new KeywordRetrievalRouter(profileSearcher, marketingSearcher, faqSearcher, reviewSearcher);
    }

    @Test
    void productProfileEvidenceDispatchesToProductProfileSearcherOnly() {
        when(profileSearcher.search(anyString(), any(), anyInt()))
                .thenReturn(List.of(hit(1L, RagChunkType.PRODUCT_PROFILE, 0.9)));

        KeywordSearchResult result = router.search(
                "饮料",
                ProductQueryCondition.empty("饮料"),
                EnumSet.of(RagChunkType.PRODUCT_PROFILE),
                5
        );

        assertThat(result.hits()).hasSize(1);
        assertThat(result.countByType()).containsEntry(RagChunkType.PRODUCT_PROFILE, 1);
        verify(profileSearcher).search(eq("饮料"), any(), eq(5));
        verify(marketingSearcher, never()).search(anyString(), any(), anyInt());
        verify(faqSearcher, never()).searchQuestion(anyString(), any(), anyInt());
        verify(faqSearcher, never()).searchAnswer(anyString(), any(), anyInt());
        verify(reviewSearcher, never()).search(anyString(), any(), anyInt());
    }

    @Test
    void productQaDispatchesToBothProfileAndMarketing() {
        when(profileSearcher.search(anyString(), any(), anyInt()))
                .thenReturn(List.of(hit(1L, RagChunkType.PRODUCT_PROFILE, 0.9)));
        when(marketingSearcher.search(anyString(), any(), anyInt()))
                .thenReturn(List.of(hit(1L, RagChunkType.MARKETING, 0.7)));

        Set<RagChunkType> evidenceTypes = new LinkedHashSet<>();
        evidenceTypes.add(RagChunkType.PRODUCT_PROFILE);
        evidenceTypes.add(RagChunkType.MARKETING);

        KeywordSearchResult result = router.search("有什么卖点",
                ProductQueryCondition.empty("有什么卖点"), evidenceTypes, 5);

        verify(profileSearcher).search(anyString(), any(), anyInt());
        verify(marketingSearcher).search(anyString(), any(), anyInt());
        assertThat(result.countByType())
                .containsEntry(RagChunkType.PRODUCT_PROFILE, 1)
                .containsEntry(RagChunkType.MARKETING, 1);
    }

    @Test
    void faqDispatchesToBothQuestionAndAnswer() {
        when(faqSearcher.searchQuestion(anyString(), any(), anyInt()))
                .thenReturn(List.of(hit(2L, RagChunkType.FAQ_QUERY, 0.6)));
        when(faqSearcher.searchAnswer(anyString(), any(), anyInt()))
                .thenReturn(List.of(hit(2L, RagChunkType.FAQ_ANSWER, 0.5)));

        Set<RagChunkType> evidenceTypes = new LinkedHashSet<>();
        evidenceTypes.add(RagChunkType.FAQ_QUERY);
        evidenceTypes.add(RagChunkType.FAQ_ANSWER);

        KeywordSearchResult result = router.search("怎么保存",
                ProductQueryCondition.empty("怎么保存"), evidenceTypes, 5);

        verify(faqSearcher).searchQuestion(anyString(), any(), anyInt());
        verify(faqSearcher).searchAnswer(anyString(), any(), anyInt());
        assertThat(result.countByType())
                .containsEntry(RagChunkType.FAQ_QUERY, 1)
                .containsEntry(RagChunkType.FAQ_ANSWER, 1);
    }

    @Test
    void reviewDispatchesToReviewSearcherOnly() {
        when(reviewSearcher.search(anyString(), any(), anyInt()))
                .thenReturn(List.of(hit(3L, RagChunkType.REVIEW, 0.5)));

        router.search("评价", ProductQueryCondition.empty("评价"),
                EnumSet.of(RagChunkType.REVIEW), 5);

        verify(reviewSearcher).search(anyString(), any(), anyInt());
        verify(profileSearcher, never()).search(anyString(), any(), anyInt());
    }

    @Test
    void singleSearcherFailureIsTolerated() {
        when(profileSearcher.search(anyString(), any(), anyInt()))
                .thenThrow(new IllegalStateException("simulated failure"));
        when(marketingSearcher.search(anyString(), any(), anyInt()))
                .thenReturn(List.of(hit(4L, RagChunkType.MARKETING, 0.4)));

        Set<RagChunkType> evidenceTypes = new LinkedHashSet<>();
        evidenceTypes.add(RagChunkType.PRODUCT_PROFILE);
        evidenceTypes.add(RagChunkType.MARKETING);

        KeywordSearchResult result = router.search("查询", ProductQueryCondition.empty("查询"),
                evidenceTypes, 5);

        // MARKETING 分支照样返回，整体不抛错；PRODUCT_PROFILE 报错被吞掉
        assertThat(result.countByType()).containsOnlyKeys(RagChunkType.MARKETING);
    }

    @Test
    void emptyQueryReturnsEmptyWithoutDispatch() {
        KeywordSearchResult result = router.search("", ProductQueryCondition.empty(""),
                EnumSet.of(RagChunkType.PRODUCT_PROFILE), 5);
        assertThat(result.isEmpty()).isTrue();
        verify(profileSearcher, never()).search(anyString(), any(), anyInt());
    }

    private static ProductSearchHit hit(Long productId, RagChunkType chunkType, double score) {
        return new ProductSearchHit(productId, null, String.valueOf(productId), score,
                chunkType.name(), null, Map.of());
    }
}
