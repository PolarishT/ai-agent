package com.bytedance.ai.graph.catalog.messaging;

import com.bytedance.ai.graph.catalog.persistence.CatalogAttributeOutboxRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogAttributeOutboxRepository;
import com.bytedance.ai.shared.properties.RagProperties;
import com.bytedance.ai.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 定时扫描 {@code catalog_attribute_outbox}：把 NEW / FAILED（next_attempt_at 已到）
 * 行投递给 RocketMQ，并按 markSending → publish → markSent / markFailed 推进状态机。
 *
 * <p>仅当 RocketMQ producer Bean 存在时（即 {@code rag.rocketmq.enabled=true} 且
 * topic 配齐）才装配；否则本 dispatcher 不会启动，outbox 行会保留为 NEW，
 * 等待人工 REST 触发或环境就位后继续投递——符合"开发环境无 RocketMQ 时降级"的设计。
 */
@Component
@ConditionalOnProperty(prefix = "rag.rocketmq", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "rag.rocketmq", name = {"endpoints"})
@ConditionalOnProperty(prefix = "rag.catalog", name = "rocket-mq-topic")
public class CatalogAttributeOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CatalogAttributeOutboxDispatcher.class);

    private final CatalogAttributeOutboxRepository outboxRepository;
    private final CatalogAttributeRocketMqProducer producer;
    private final RagProperties ragProperties;

    public CatalogAttributeOutboxDispatcher(
            CatalogAttributeOutboxRepository outboxRepository,
            CatalogAttributeRocketMqProducer producer,
            RagProperties ragProperties
    ) {
        this.outboxRepository = outboxRepository;
        this.producer = producer;
        this.ragProperties = ragProperties;
    }

    @Scheduled(
            fixedDelayString = "${rag.catalog.dispatch-fixed-delay-millis:1000}",
            initialDelayString = "${rag.catalog.dispatch-fixed-delay-millis:1000}"
    )
    public void dispatchPending() {
        int batchSize = ragProperties.catalog().outboxBatchSize();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<CatalogAttributeOutboxRecord> rows = outboxRepository.findDispatchable(now, batchSize);
        if (rows.isEmpty()) {
            return;
        }
        log.debug("catalog outbox dispatch start: batchSize={}, candidates={}", batchSize, rows.size());
        for (CatalogAttributeOutboxRecord row : rows) {
            dispatchOne(row);
        }
    }

    private void dispatchOne(CatalogAttributeOutboxRecord row) {
        if (!outboxRepository.markSending(row.id())) {
            // 其它 dispatcher 已抢到，跳过。
            return;
        }
        CatalogAttributeMessagePayload payload = new CatalogAttributeMessagePayload(
                row.productId(),
                "outbox-dispatcher",
                System.currentTimeMillis()
        );
        try {
            String messageId = producer.publish(payload);
            outboxRepository.markSent(row.id(), messageId);
        } catch (RuntimeException exception) {
            long backoffMillis = ragProperties.catalog().failureBackoffMillis();
            OffsetDateTime nextAttemptAt = OffsetDateTime.now(ZoneOffset.UTC).plusNanos(backoffMillis * 1_000_000L);
            String reason = RagLogHelper.errorSummary(exception);
            outboxRepository.markFailed(row.id(), reason, nextAttemptAt);
            log.warn(
                    "catalog outbox dispatch failed: outboxId={}, productId={}, reason={}, nextAttemptAt={}",
                    row.id(),
                    row.productId(),
                    reason,
                    nextAttemptAt
            );
        }
    }
}
