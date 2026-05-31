package com.bytedance.ai.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RateLimiterStoreTest {

    private static final List<RateRule> TIERS = List.of(
            new RateRule(60, 6),
            new RateRule(300, 16),
            new RateRule(600, 25)
    );

    private final long[] clock = {0L};
    private final RateLimiterStore store = new RateLimiterStore(() -> clock[0]);

    private void acquire() {
        store.acquireOrThrow("k", TIERS, "too many");
    }

    @Test
    void allowsUpToFirstWindowLimitThenRejects() {
        for (int i = 0; i < 6; i++) {
            assertThatCode(this::acquire).doesNotThrowAnyException();
        }
        assertThatThrownBy(this::acquire)
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(ex -> {
                    RateLimitExceededException rle = (RateLimitExceededException) ex;
                    assertThat(rle.getReason()).isEqualTo("too many");
                    // 第 7 次在 t=0 被拒，1min 窗口还剩 60s
                    assertThat(rle.getRetryAfterSeconds()).isEqualTo(60);
                });
    }

    @Test
    void rejectedRequestDoesNotConsumeOtherWindows() {
        // 打满 1min 窗口（6 次），随后所有请求都被 1min 窗口拒绝
        for (int i = 0; i < 6; i++) {
            acquire();
        }
        for (int i = 0; i < 30; i++) {
            assertThatThrownBy(this::acquire).isInstanceOf(RateLimitExceededException.class);
        }
        // 1min 窗口滚动后，5min/10min 窗口应仍只计了最初 6 次（被拒的没消耗）
        clock[0] = 60_000L;
        for (int i = 0; i < 6; i++) {
            assertThatCode(this::acquire).doesNotThrowAnyException(); // 又一分钟内 6 次
        }
    }

    @Test
    void secondTierCapsAcrossMinutes() {
        // 每分钟打满 6 次：第 1、2 分钟共 12 次通过；第 3 分钟第 5 次（累计 17）触发 5min 窗口(16)上限
        for (int minute = 0; minute < 3; minute++) {
            clock[0] = minute * 60_000L;
            for (int i = 0; i < 6; i++) {
                if (minute == 2 && i == 4) {
                    assertThatThrownBy(this::acquire)
                            .isInstanceOf(RateLimitExceededException.class)
                            .satisfies(ex -> assertThat(((RateLimitExceededException) ex).getRetryAfterSeconds())
                                    // 5min 窗口从 t=0 开，到第 3 分钟剩 300-120=180s
                                    .isEqualTo(180));
                    return;
                }
                acquire();
            }
        }
    }

    @Test
    void firstWindowResetsAfterItsDuration() {
        for (int i = 0; i < 6; i++) {
            acquire();
        }
        assertThatThrownBy(this::acquire).isInstanceOf(RateLimitExceededException.class);
        // 跨过 1min 窗口
        clock[0] = 60_000L;
        assertThatCode(this::acquire).doesNotThrowAnyException();
    }

    @Test
    void differentKeysAreIsolated() {
        for (int i = 0; i < 6; i++) {
            store.acquireOrThrow("user-a", TIERS, "too many");
        }
        assertThatThrownBy(() -> store.acquireOrThrow("user-a", TIERS, "too many"))
                .isInstanceOf(RateLimitExceededException.class);
        // 另一个键不受影响
        assertThatCode(() -> store.acquireOrThrow("user-b", TIERS, "too many"))
                .doesNotThrowAnyException();
    }

    @Test
    void emptyRulesAreNoOp() {
        for (int i = 0; i < 1000; i++) {
            assertThatCode(() -> store.acquireOrThrow("k", List.of(), "too many"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void idleKeysAreEvicted() {
        store.acquireOrThrow("k", TIERS, "too many");
        assertThat(store.trackedKeyCount()).isEqualTo(1);
        // 远超 maxWindow(600s) + cleanup(60s) 后再访问任意键，触发清理
        clock[0] = 700_000L + 60_000L + 1L;
        store.acquireOrThrow("other", TIERS, "too many");
        assertThat(store.trackedKeyCount()).isEqualTo(1); // 旧的 "k" 被回收，只剩 "other"
    }
}
