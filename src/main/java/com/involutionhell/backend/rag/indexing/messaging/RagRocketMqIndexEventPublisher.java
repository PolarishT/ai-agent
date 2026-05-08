package com.involutionhell.backend.rag.indexing.messaging;

import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.shared.support.RagJsonCodec;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.core.RocketMQClientTemplate;
import org.apache.rocketmq.client.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 启用 RocketMQ 后，将索引任务投递到消息队列。
 */
@Service
@ConditionalOnProperty(prefix = "rag.rocketmq", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "rag.rocketmq", name = {"endpoints", "topic"})
public class RagRocketMqIndexEventPublisher implements RagIndexEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RagRocketMqIndexEventPublisher.class);

    private final RocketMQClientTemplate rocketMQClientTemplate;
    private final RagJsonCodec jsonCodec;
    private final String topic;
    private final String tag;

    public RagRocketMqIndexEventPublisher(
            RocketMQClientTemplate rocketMQClientTemplate,
            RagJsonCodec jsonCodec,
            RagProperties ragProperties
    ) {
        this.rocketMQClientTemplate = rocketMQClientTemplate;
        this.jsonCodec = jsonCodec;
        this.topic = ragProperties.rocketMq().topic();
        this.tag = ragProperties.rocketMq().tag();
    }

    @Override
    public String publish(Long documentId, String contentSha256) {
        try {
            log.info(
                    "Publishing RAG index message to RocketMQ: documentId={}, contentSha={}, topic={}, tag={}",
                    documentId,
                    RagLogHelper.shortSha(contentSha256),
                    topic,
                    tag
            );
            String payload = jsonCodec.write(
                    new RagIndexMessage(documentId, contentSha256, OffsetDateTime.now())
            );
            SendReceipt receipt = rocketMQClientTemplate.syncSendFifoMessage(
                    topic + ":" + tag,
                    MessageBuilder.withPayload(payload)
                            .setHeader(RocketMQHeaders.TAGS, tag)
                            .setHeader(RocketMQHeaders.KEYS, "rag-doc-" + documentId)
                            .build(), "involutionhell-doc-index" + documentId
            );
            log.debug("RAG index message published to RocketMQ: documentId={}, contentSha={}, topic={}", documentId, RagLogHelper.shortSha(contentSha256), topic);
            return receipt == null || receipt.getMessageId() == null ? null : receipt.getMessageId().toString();
        } catch (Exception exception) {
            throw new IllegalStateException("RocketMQ 索引消息发送失败: " + exception.getMessage(), exception);
        }
    }
}
