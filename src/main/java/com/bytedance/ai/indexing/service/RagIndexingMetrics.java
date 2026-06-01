package com.bytedance.ai.indexing.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * RAG 索引链路的 Micrometer 指标封装。
 *
 * <p>该组件集中记录索引耗时、切片数量、Milvus 写入、失败重试、outbox 分发、
 * 补偿恢复和 embedding cache 命中情况。未装配 {@link MeterRegistry} 时所有方法静默跳过，
 * 便于单元测试和轻量运行环境复用同一套业务代码。
 */
@Component
public class RagIndexingMetrics {

    private final MeterRegistry meterRegistry;

    public RagIndexingMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    /**
     * 记录一次索引成功的耗时和生成切片数。
     *
     * @param chunkCount 本次生成的切片数量
     * @param duration   本次索引总耗时
     */
    public void recordIndexSuccess(int chunkCount, Duration duration) {
        if (meterRegistry == null) {
            return;
        }
        Timer.builder("rag.indexing.duration")
                .tag("outcome", "success")
                .register(meterRegistry)
                .record(duration);
        DistributionSummary.builder("rag.indexing.chunk.count")
                .register(meterRegistry)
                .record(chunkCount);
    }

    /**
     * 记录一次 Milvus 写入的耗时和写入切片数。
     *
     * @param chunkCount   写入切片数量
     * @param duration     Milvus 写入耗时
     * @param cacheEnabled 本次写入是否启用了 embedding cache
     */
    public void recordMilvusWrite(int chunkCount, Duration duration, boolean cacheEnabled) {
        if (meterRegistry == null) {
            return;
        }
        Timer.builder("rag.indexing.milvus.write.duration")
                .tag("cache", String.valueOf(cacheEnabled))
                .register(meterRegistry)
                .record(duration);
        DistributionSummary.builder("rag.indexing.milvus.write.chunks")
                .register(meterRegistry)
                .record(chunkCount);
    }

    /**
     * 记录一次可重试索引失败。
     *
     * @param reason 失败分类原因
     */
    public void recordRetry(String reason) {
        increment("rag.indexing.retry.count", "reason", reason);
    }

    /**
     * 记录一次索引失败。
     *
     * @param reason    失败分类原因
     * @param retryable 是否可由后续恢复机制重试
     */
    public void recordFailure(String reason, boolean retryable) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("rag.indexing.failure.count")
                .tag("reason", reason)
                .tag("retryable", String.valueOf(retryable))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录一次 MQ 消息解析失败。
     *
     * @param terminal 是否已经达到阈值并进入人工介入终态
     */
    public void recordMessageParseFailure(boolean terminal) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("rag.indexing.message.parse_failure.count")
                .tag("terminal", String.valueOf(terminal))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录一次 outbox 成功分发。
     */
    public void recordOutboxDispatchSuccess() {
        increment("rag.indexing.outbox.dispatch.count", "outcome", "success");
    }

    /**
     * 记录一次 outbox 分发失败。
     *
     * @param phase 失败发生阶段
     */
    public void recordOutboxDispatchFailure(String phase) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("rag.indexing.outbox.dispatch.count")
                .tag("outcome", "failure")
                .tag("phase", phase == null || phase.isBlank() ? "unknown" : phase)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录补偿任务扫描到的候选文档数量。
     *
     * @param category 文档状态分类
     * @param count    扫描命中数量
     */
    public void recordRecoveryScan(String category, int count) {
        if (meterRegistry == null || count <= 0) {
            return;
        }
        Counter.builder("rag.indexing.recovery.scan.count")
                .tag("category", normalizeTag(category))
                .register(meterRegistry)
                .increment(count);
    }

    /**
     * 记录补偿任务对单个文档的处理结果。
     *
     * @param category 文档状态分类
     * @param outcome  处理结果
     */
    public void recordRecoveryOutcome(String category, String outcome) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("rag.indexing.recovery.outcome.count")
                .tag("category", normalizeTag(category))
                .tag("outcome", normalizeTag(outcome))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录删除清理流程的处理结果。
     *
     * @param outcome 处理结果
     */
    public void recordDeleteCleanup(String outcome) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("rag.indexing.delete.cleanup.count")
                .tag("outcome", normalizeTag(outcome))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录 embedding cache 命中数量。
     *
     * @param count 命中数量
     */
    public void recordCacheHits(int count) {
        increment("rag.embedding.cache.hit.count", count);
    }

    /**
     * 记录 embedding cache 未命中数量。
     *
     * @param count 未命中数量
     */
    public void recordCacheMisses(int count) {
        increment("rag.embedding.cache.miss.count", count);
    }

    private void increment(String name, int amount) {
        if (meterRegistry == null || amount <= 0) {
            return;
        }
        Counter.builder(name)
                .register(meterRegistry)
                .increment(amount);
    }

    private void increment(String name, String tagKey, String tagValue) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(name)
                .tag(tagKey, tagValue)
                .register(meterRegistry)
                .increment();
    }

    private String normalizeTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
