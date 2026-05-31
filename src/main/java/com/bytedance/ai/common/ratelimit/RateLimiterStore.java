package com.bytedance.ai.common.ratelimit;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * 内存版多时间窗限流器（单机）。每个限流键维护一组「定窗计数器」（fixed-window counter），
 * 任一窗口超限即整体拒绝。
 *
 * <p>采用「先全量检查、全通过再一起 +1」的两段式，保证被拒绝的请求不会消耗其它窗口的配额。
 * 对同一个键的并发请求用 {@link ReentrantLock} 串行化；不同键互不阻塞。
 *
 * <p>键数量随 SpEL 维度（如 userId）增长，因此带惰性清理：每 {@code CLEANUP_INTERVAL_MS}
 * 最多触发一次扫描，回收长时间空闲的键，避免 map 无限膨胀。
 *
 * <p>注意：内存实现仅在单实例内生效；多副本部署时各实例独立计数，需要全局限流应换 Redis 等共享存储。
 */
@Component
public class RateLimiterStore {

    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nowMillis;
    private final AtomicLong lastCleanupAt = new AtomicLong(0L);

    public RateLimiterStore() {
        this(System::currentTimeMillis);
    }

    /**
     * 测试用构造器：注入可控时钟。
     */
    RateLimiterStore(LongSupplier nowMillis) {
        this.nowMillis = nowMillis;
    }

    /**
     * 对 {@code key} 申请一次配额；任一窗口超限则抛 {@link RateLimitExceededException}。
     *
     * @param key     限流键（已含方法命名空间，避免不同接口串扰）
     * @param rules   该键的时间窗规则
     * @param message 超限提示语
     */
    public void acquireOrThrow(String key, List<RateRule> rules, String message) {
        if (rules == null || rules.isEmpty()) {
            return;
        }
        long now = nowMillis.getAsLong();
        maybeCleanup(now);
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(rules));
        bucket.acquireOrThrow(now, message);
    }

    int trackedKeyCount() {
        return buckets.size();
    }

    private void maybeCleanup(long now) {
        long last = lastCleanupAt.get();
        if (now - last < CLEANUP_INTERVAL_MS) {
            return;
        }
        if (!lastCleanupAt.compareAndSet(last, now)) {
            return; // 已有其它线程在清理，跳过
        }
        buckets.forEach((key, bucket) -> {
            if (bucket.isIdle(now)) {
                buckets.remove(key, bucket);
            }
        });
    }

    private static final class Bucket {

        private final ReentrantLock lock = new ReentrantLock();
        private final Slot[] slots;
        private final long maxWindowMs;
        private volatile long lastAccessMs;

        Bucket(List<RateRule> rules) {
            this.slots = new Slot[rules.size()];
            long max = 0L;
            for (int i = 0; i < rules.size(); i++) {
                RateRule rule = rules.get(i);
                this.slots[i] = new Slot(rule.windowMillis(), rule.permits());
                max = Math.max(max, rule.windowMillis());
            }
            this.maxWindowMs = max;
        }

        void acquireOrThrow(long now, String message) {
            lock.lock();
            try {
                lastAccessMs = now;
                long retryAfterMs = 0L;
                // 阶段一：滚动过期窗口 + 检查是否会超限（不修改计数）
                for (Slot slot : slots) {
                    if (now - slot.windowStartMs >= slot.sizeMs) {
                        slot.windowStartMs = now;
                        slot.count = 0;
                    }
                    if (slot.count + 1 > slot.permits) {
                        retryAfterMs = Math.max(retryAfterMs, slot.windowStartMs + slot.sizeMs - now);
                    }
                }
                if (retryAfterMs > 0L) {
                    long retryAfterSeconds = (retryAfterMs + 999L) / 1000L; // 向上取整
                    throw new RateLimitExceededException(message, retryAfterSeconds);
                }
                // 阶段二：全部通过，统一提交
                for (Slot slot : slots) {
                    slot.count++;
                }
            } finally {
                lock.unlock();
            }
        }

        boolean isIdle(long now) {
            return now - lastAccessMs > maxWindowMs + CLEANUP_INTERVAL_MS;
        }
    }

    private static final class Slot {

        private final long sizeMs;
        private final int permits;
        private long windowStartMs;
        private int count;

        Slot(long sizeMs, int permits) {
            this.sizeMs = sizeMs;
            this.permits = permits;
        }
    }
}
