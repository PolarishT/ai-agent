package com.involutionhell.backend.rag.retrieval.support;

import com.involutionhell.backend.rag.retrieval.api.RagResponseNoticeView;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 当前 RAG 请求的降级反馈收集器。
 */
public final class RagRequestFeedbacks {

    private RagRequestFeedbacks() {
    }

    public static Set<RagResponseNoticeView> begin() {
        return Collections.synchronizedSet(new LinkedHashSet<>());
    }

    public static void recordTimeout(Set<RagResponseNoticeView> notices, String stage, String message) {
        record(notices, stage, "timeout", message);
    }

    public static void record(Set<RagResponseNoticeView> notices, String stage, String code, String message) {
        if (notices == null) {
            return;
        }
        notices.add(new RagResponseNoticeView(stage, code, message));
    }

    public static List<RagResponseNoticeView> snapshot(Set<RagResponseNoticeView> notices) {
        if (notices == null || notices.isEmpty()) {
            return List.of();
        }
        synchronized (notices) {
            return List.copyOf(notices);
        }
    }
}
