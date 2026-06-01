package com.bytedance.ai.indexing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 索引 Outbox 定时分发器。
 *
 * <p>仅在 RocketMQ 与 outbox 均开启时装配，定期扫描可投递事件并委托
 * {@link RagIndexOutboxService} 完成状态推进、消息发送和失败重试记录。
 */
@Component
@ConditionalOnProperty(prefix = "rag.rocketmq", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "rag.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagIndexOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RagIndexOutboxDispatcher.class);

    private final RagIndexOutboxService outboxService;

    public RagIndexOutboxDispatcher(RagIndexOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    /**
     * 按配置间隔分发一批待发送 outbox 事件。
     */
    @Scheduled(fixedDelayString = "${rag.outbox.dispatch-fixed-delay-millis:1000}")
    public void dispatchPending() {
        int dispatched = outboxService.dispatchPendingBatch();
        if (dispatched > 0) {
            log.debug("RAG outbox dispatcher flushed pending events: count={}", dispatched);
        }
    }
}
