package com.bytedance.ai.indexing.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 单次离线索引尝试失败时抛出的异常，携带当前阶段和可恢复性信息。
 *
 * <p>异常本身不绑定具体恢复机制。MQ 模式可以据此决定是否交给消息系统重试，
 * 直连模式则由上层调用方决定是否异步记录、跳过或终止。
 */
@Getter
@Setter
public class RagIndexAttemptException extends RuntimeException {

    private final RagIndexStage stage;
    private final boolean recoverable;
    private final String reason;
    private final String errorMessage;

    public RagIndexAttemptException(
            RagIndexStage stage,
            boolean recoverable,
            String reason,
            String errorMessage,
            Throwable cause
    ) {
        super(errorMessage, cause);
        this.stage = stage;
        this.recoverable = recoverable;
        this.reason = reason;
        this.errorMessage = errorMessage;
    }

    public boolean isRetryable() {
        return recoverable;
    }
}
