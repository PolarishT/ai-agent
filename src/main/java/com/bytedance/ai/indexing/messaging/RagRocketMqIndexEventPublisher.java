package com.bytedance.ai.indexing.messaging;

import com.bytedance.ai.shared.properties.RagProperties;
import com.bytedance.ai.shared.support.RagJsonCodec;
import com.bytedance.ai.shared.support.RagLogFields;
import com.bytedance.ai.shared.support.RagLogHelper;
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
            log.atInfo()
                    .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.message.publish.started")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_STARTED)
                    .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, contentSha256))
                    .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                    .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(contentSha256))
                    .addKeyValue("rag.rocketmq_topic", topic)
                    .addKeyValue("rag.rocketmq_tag", tag)
                    .log("Publishing RAG index message to RocketMQ");
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
            String messageId = receipt == null || receipt.getMessageId() == null ? null : receipt.getMessageId().toString();
            log.atInfo()
                    .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.message.publish.completed")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SUCCESS)
                    .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, contentSha256))
                    .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                    .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(contentSha256))
                    .addKeyValue(RagLogFields.RAG_MESSAGE_ID, messageId)
                    .addKeyValue("rag.rocketmq_topic", topic)
                    .addKeyValue("rag.rocketmq_tag", tag)
                    .log("RAG index message published to RocketMQ");
            return messageId;
        } catch (Exception exception) {
            log.atError()
                    .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.message.publish.failed")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_FAILURE)
                    .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, contentSha256))
                    .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                    .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(contentSha256))
                    .addKeyValue("rag.rocketmq_topic", topic)
                    .addKeyValue("rag.rocketmq_tag", tag)
                    .addKeyValue(RagLogFields.RAG_ERROR_SUMMARY, RagLogHelper.errorSummary(exception))
                    .setCause(exception)
                    .log("RAG index message publish to RocketMQ failed");
            throw new IllegalStateException("RocketMQ 索引消息发送失败: " + exception.getMessage(), exception);
        }
    }
}
