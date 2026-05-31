package com.bytedance.ai.graph.catalog.messaging;

import com.bytedance.ai.shared.properties.RagProperties;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.core.RocketMQClientTemplate;
import org.apache.rocketmq.client.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/**
 * 把 {@link CatalogAttributeMessagePayload} 投递到 RocketMQ。
 *
 * <p>仅在 {@code rag.rocketmq.enabled=true} 且 endpoints + catalog topic 配齐时装配；
 * 缺一不可时该 Bean 不存在，dispatcher 自身会进入"不可调度"状态保持空转，
 * 不阻塞应用启动，便于本地 dev 在没有 RocketMQ 时也能跑导入。
 */
@Service
@ConditionalOnProperty(prefix = "rag.rocketmq", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "rag.rocketmq", name = {"endpoints"})
@ConditionalOnProperty(prefix = "rag.catalog", name = "rocket-mq-topic")
public class CatalogAttributeRocketMqProducer {

    private static final Logger log = LoggerFactory.getLogger(CatalogAttributeRocketMqProducer.class);

    private final RocketMQClientTemplate rocketMQClientTemplate;
    private final RagJsonCodec jsonCodec;
    private final String topic;
    private final String tag;

    public CatalogAttributeRocketMqProducer(
            RocketMQClientTemplate rocketMQClientTemplate,
            RagJsonCodec jsonCodec,
            RagProperties ragProperties
    ) {
        this.rocketMQClientTemplate = rocketMQClientTemplate;
        this.jsonCodec = jsonCodec;
        this.topic = ragProperties.catalog().rocketMqTopic();
        this.tag = ragProperties.catalog().rocketMqTag();
    }

    /**
     * 同步发送一条抽属性消息，返回 RocketMQ 返回的 messageId（用于回写 outbox.message_id）。
     */
    public String publish(CatalogAttributeMessagePayload payload) {
        try {
            String body = jsonCodec.write(payload);
            SendReceipt receipt = rocketMQClientTemplate.syncSendNormalMessage(
                    topic + ":" + tag,
                    MessageBuilder.withPayload(body)
                            .setHeader(RocketMQHeaders.TAGS, tag)
                            .setHeader(RocketMQHeaders.KEYS, "catalog-product-" + payload.productId())
                            .build()
            );
            String messageId = receipt == null || receipt.getMessageId() == null
                    ? null
                    : receipt.getMessageId().toString();
            log.info(
                    "catalog attribute message published: productId={}, topic={}, messageId={}",
                    payload.productId(),
                    topic,
                    messageId
            );
            return messageId;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "catalog attribute message publish failed: productId=" + payload.productId()
                            + ", reason=" + exception.getMessage(),
                    exception
            );
        }
    }
}
