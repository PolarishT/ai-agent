package com.involutionhell.backend.rag.retrieval.support;

import com.involutionhell.backend.rag.retrieval.api.RagResponseNoticeView;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 当前 RAG 请求的降级反馈收集器。
 *
 * <p>使用 InheritableThreadLocal 让 query branch / hybrid branch 的虚拟线程也能把降级提示回传到当前请求。
 * Reactor 的 timeout / fallback 信号可能不在继承链上的线程执行，因此调用方会保留 begin 返回的集合并在需要时 restore。
 */
public final class RagRequestFeedbacks {

    private static final InheritableThreadLocal<Set<RagResponseNoticeView>> CURRENT = new InheritableThreadLocal<>();

    private RagRequestFeedbacks() {
    }

    public static Set<RagResponseNoticeView> begin() {
        Set<RagResponseNoticeView> notices = Collections.synchronizedSet(new LinkedHashSet<>());
        CURRENT.set(notices);
        return notices;
    }

    /**
     * 将当前执行线程重新绑定到请求级反馈集合。
     */
    public static void restore(Set<RagResponseNoticeView> notices) {
        if (notices != null) {
            CURRENT.set(notices);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void recordTimeout(String stage, String message) {
        record(stage, "timeout", message);
    }

    public static void record(String stage, String code, String message) {
        Set<RagResponseNoticeView> notices = CURRENT.get();
        if (notices == null) {
            return;
        }
        notices.add(new RagResponseNoticeView(stage, code, message));
    }

    public static List<RagResponseNoticeView> snapshot() {
        Set<RagResponseNoticeView> notices = CURRENT.get();
        if (notices == null || notices.isEmpty()) {
            return List.of();
        }
        synchronized (notices) {
            return List.copyOf(notices);
        }
    }
}
