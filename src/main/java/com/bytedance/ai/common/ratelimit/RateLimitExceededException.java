package com.bytedance.ai.common.ratelimit;

import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 触发限流时抛出，映射为 HTTP 429 Too Many Requests。
 *
 * <p>继承 {@link ResponseStatusException}：项目里 {@code RagExceptionHandler}
 * 已统一把 {@code ResponseStatusException} 转成 {@code ApiResponse.fail(reason)} + 对应状态码，
 * WebFlux 侧也会被框架原生识别成 429，因此无需新增任何异常处理器。
 *
 * <p>同时附带 {@code Retry-After}（秒）响应头，提示客户端最早可重试时间。
 */
@Getter
public class RateLimitExceededException extends ResponseStatusException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(String reason, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, reason);
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    @Override
    public @NonNull HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfterSeconds > 0) {
            headers.add(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        }
        return headers;
    }
}
