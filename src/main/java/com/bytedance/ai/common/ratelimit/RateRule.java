package com.bytedance.ai.common.ratelimit;

/**
 * 限流规则的纯值对象（{@link RateWindow} 注解的运行期等价物）。
 *
 * <p>把注解翻译成 record 让 {@code RateLimiterStore} 与注解解耦，便于单元测试直接构造规则。
 *
 * @param seconds 时间窗长度（秒），必须为正
 * @param permits 该时间窗内允许的最大请求次数，必须为正
 */
public record RateRule(int seconds, int permits) {

    public RateRule {
        if (seconds <= 0) {
            throw new IllegalArgumentException("RateRule.seconds 必须为正: " + seconds);
        }
        if (permits <= 0) {
            throw new IllegalArgumentException("RateRule.permits 必须为正: " + permits);
        }
    }

    public long windowMillis() {
        return seconds * 1000L;
    }
}
