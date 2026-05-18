package com.involutionhell.backend.rag.retrieval.support;

import com.involutionhell.backend.rag.retrieval.api.RagResponseNoticeView;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagRequestFeedbacksTests {

    @Test
    void keepsExplicitFeedbackCollectionsIsolated() {
        Set<RagResponseNoticeView> first = RagRequestFeedbacks.begin();
        Set<RagResponseNoticeView> second = RagRequestFeedbacks.begin();

        RagRequestFeedbacks.record(first, "retrieve", "timeout", "first request");
        RagRequestFeedbacks.record(second, "answer_generate", "error", "second request");

        assertThat(RagRequestFeedbacks.snapshot(first))
                .extracting(RagResponseNoticeView::message)
                .containsExactly("first request");
        assertThat(RagRequestFeedbacks.snapshot(second))
                .extracting(RagResponseNoticeView::message)
                .containsExactly("second request");
    }
}
