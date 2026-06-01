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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        // 用同步 executor 跑测试：保留并行 API 形态，但避免单测引入虚拟线程调度的不确定性
        router = new KeywordRetrievalRouter(profileSearcher, marketingSearcher, faqSearcher, reviewSearcher,
                Runnable::run);
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

    @Test
    void multipleEvidenceTypesRunInParallelNotSequentially() throws InterruptedException {
        // 真实并发场景：两路 searcher 都在「latch.await」上互等。
        // 如果 router 是串行的（先跑完 PROFILE 才跑 MARKETING），第一路会永远 await，超时失败。
        // 如果 router 是并行的，两路同时开始，latch 同时归零，两路都完成。
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch releaseGate = new CountDownLatch(1);

        when(profileSearcher.search(anyString(), any(), anyInt())).thenAnswer(inv -> {
            bothStarted.countDown();
            boolean opened = releaseGate.await(2, TimeUnit.SECONDS);
            if (!opened) {
                throw new IllegalStateException("PROFILE searcher 等待并发对端超时，说明 router 仍在串行");
            }
            return List.of(hit(1L, RagChunkType.PRODUCT_PROFILE, 0.9));
        });
        when(marketingSearcher.search(anyString(), any(), anyInt())).thenAnswer(inv -> {
            bothStarted.countDown();
            boolean opened = releaseGate.await(2, TimeUnit.SECONDS);
            if (!opened) {
                throw new IllegalStateException("MARKETING searcher 等待并发对端超时，说明 router 仍在串行");
            }
            return List.of(hit(2L, RagChunkType.MARKETING, 0.6));
        });

        // 用真实线程池（每路一个线程），让两路确实并行；router 内部仍用 supplyAsync 分发。
        var executor = Executors.newFixedThreadPool(2);
        try {
            KeywordRetrievalRouter parallelRouter = new KeywordRetrievalRouter(
                    profileSearcher, marketingSearcher, faqSearcher, reviewSearcher, executor);

            // 在另一个线程跑 router.search，因为它内部会等两路 future；主线程负责开闸。
            var resultRef = new java.util.concurrent.atomic.AtomicReference<KeywordSearchResult>();
            Thread caller = new Thread(() -> {
                Set<RagChunkType> evidenceTypes = new LinkedHashSet<>();
                evidenceTypes.add(RagChunkType.PRODUCT_PROFILE);
                evidenceTypes.add(RagChunkType.MARKETING);
                resultRef.set(parallelRouter.search("白葡萄味气泡水",
                        ProductQueryCondition.empty("白葡萄味气泡水"), evidenceTypes, 5));
            });
            caller.start();

            // 等两路都进入 await（证明真正并行启动了），再放闸
            assertThat(bothStarted.await(2, TimeUnit.SECONDS)).as("两路应同时启动").isTrue();
            releaseGate.countDown();
            caller.join(2_000);

            assertThat(resultRef.get()).isNotNull();
            assertThat(resultRef.get().countByType())
                    .containsEntry(RagChunkType.PRODUCT_PROFILE, 1)
                    .containsEntry(RagChunkType.MARKETING, 1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static ProductSearchHit hit(Long productId, RagChunkType chunkType, double score) {
        return new ProductSearchHit(productId, null, String.valueOf(productId), score,
                chunkType.name(), null, Map.of());
    }
}
