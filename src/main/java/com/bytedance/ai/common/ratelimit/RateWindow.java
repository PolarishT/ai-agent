package com.bytedance.ai.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 单个限流时间窗：{@code seconds} 秒内最多允许 {@code permits} 次。
 *
 * <p>仅作为 {@link RateLimit#windows()} 的成员使用，不单独标注在方法上。
 *
 * @see RateLimit
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface RateWindow {

    /**
     * 时间窗长度（秒），必须为正。
     */
    int seconds();

    /**
     * 该时间窗内允许的最大请求次数，必须为正。
     */
    int permits();
}
