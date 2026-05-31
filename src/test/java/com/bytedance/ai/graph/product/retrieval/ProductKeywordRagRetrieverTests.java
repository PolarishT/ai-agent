package com.bytedance.ai.graph.product.retrieval;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductQueryIntent;
import com.bytedance.ai.graph.product.retrieval.scenario.IntentEvidenceTypeResolver;
import com.bytedance.ai.graph.product.retrieval.scenario.KeywordRetrievalRouter;
import com.bytedance.ai.graph.product.retrieval.scenario.KeywordSearchResult;
import com.bytedance.ai.shared.metadata.RagChunkType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 ProductKeywordRagRetriever 将 ProductQueryIntent 翻译为正确的 RagChunkType 集合
 * 并委托给 KeywordRetrievalRouter；底层 SQL 行为由各 searcher 自己的测试覆盖。
 */
class ProductKeywordRagRetrieverTests {

    private KeywordRetrievalRouter router;
    private IntentEvidenceTypeResolver resolver;
    private ProductKeywordRagRetriever retriever;

    @BeforeEach
    void setUp() {
        router = mock(KeywordRetrievalRouter.class);
        resolver = new IntentEvidenceTypeResolver();
        when(router.search(anyString(), any(), any(), anyInt())).thenReturn(KeywordSearchResult.empty());
        retriever = new ProductKeywordRagRetriever(router, resolver);
    }

    @Test
    void productSearchIntentMapsToProductProfileOnly() {
        retriever.search("饮料", ProductQueryCondition.empty("饮料"), ProductQueryIntent.PRODUCT_SEARCH, 5);
        assertThat(capturedEvidenceTypes()).containsExactly(RagChunkType.PRODUCT_PROFILE);
    }

    @Test
    void productRecommendIntentMapsToProfileAndMarketing() {
        retriever.search("白葡萄味气泡水", ProductQueryCondition.empty("白葡萄味气泡水"),
                ProductQueryIntent.PRODUCT_RECOMMEND, 5);
        // PROFILE 在前（决定可售商品），MARKETING 紧随（补充推荐理由 / 卖点）
        assertThat(capturedEvidenceTypes())
                .containsExactly(RagChunkType.PRODUCT_PROFILE, RagChunkType.MARKETING);
    }

    @Test
    void inventoryCheckIntentMapsToProductProfileOnly() {
        retriever.search("还有货吗", ProductQueryCondition.empty("还有货吗"),
                ProductQueryIntent.INVENTORY_CHECK, 5);
        assertThat(capturedEvidenceTypes()).containsExactly(RagChunkType.PRODUCT_PROFILE);
    }

    @Test
    void priceQueryIntentMapsToProductProfileOnly() {
        retriever.search("多少钱", ProductQueryCondition.empty("多少钱"),
                ProductQueryIntent.PRICE_QA, 5);
        assertThat(capturedEvidenceTypes()).containsExactly(RagChunkType.PRODUCT_PROFILE);
    }

    @Test
    void productQaIntentMapsToProductProfileAndMarketing() {
        retriever.search("有什么卖点", ProductQueryCondition.empty("有什么卖点"),
                ProductQueryIntent.PRODUCT_QA, 5);
        assertThat(capturedEvidenceTypes())
                .containsExactly(RagChunkType.PRODUCT_PROFILE, RagChunkType.MARKETING);
    }

    @Test
    void reviewQaIntentMapsToReviewOnly() {
        retriever.search("评价如何", ProductQueryCondition.empty("评价如何"),
                ProductQueryIntent.REVIEW_QA, 5);
        assertThat(capturedEvidenceTypes()).containsExactly(RagChunkType.REVIEW);
    }

    @Test
    void faqQaIntentMapsToFaqQueryAndAnswer() {
        retriever.search("怎么保存", ProductQueryCondition.empty("怎么保存"),
                ProductQueryIntent.FAQ_QA, 5);
        assertThat(capturedEvidenceTypes())
                .containsExactly(RagChunkType.FAQ_QUERY, RagChunkType.FAQ_ANSWER);
    }

    @Test
    void nullIntentFallsBackToProductProfile() {
        retriever.search("饮料", ProductQueryCondition.empty("饮料"), null, 5);
        assertThat(capturedEvidenceTypes()).containsExactly(RagChunkType.PRODUCT_PROFILE);
    }

    @SuppressWarnings("unchecked")
    private Set<RagChunkType> capturedEvidenceTypes() {
        ArgumentCaptor<Set<RagChunkType>> captor = ArgumentCaptor.forClass(Set.class);
        verify(router).search(anyString(), any(), captor.capture(), anyInt());
        // LinkedHashSet 的 iteration 顺序即插入顺序，containsExactly 会按此校验
        return captor.getValue();
    }
}
