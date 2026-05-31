package com.bytedance.ai.graph.catalog.messaging;

import com.bytedance.ai.graph.catalog.persistence.CatalogProductRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductRepository;
import com.bytedance.ai.graph.catalog.service.LlmAttributeExtractor;
import com.bytedance.ai.shared.properties.RagProperties;
import com.bytedance.ai.shared.support.RagJsonCodec;
import com.bytedance.ai.shared.support.RagLogHelper;
import org.apache.rocketmq.client.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * 消费 catalog 抽属性消息：调 LLM 抽 attributes_json 并回填到 catalog_product。
 *
 * <p>幂等 / 并发：{@link CatalogProductRepository#markAttributeExtractionRunning} 走原子
 * UPDATE，多个消费者并发投递时只有
 * 一个能抢到 RUNNING；其余直接 ack 返回 SUCCESS（重复消息不会重复调 LLM）。
 *
 * <p>失败重试：抛出 RuntimeException 时返回 {@link ConsumeResult#FAILURE}，
 * RocketMQ 自动重投递，最多到 {@code rag.rocketmq.max-attempt-times}；耗尽后由
 * RocketMQ 进入死信队列或丢弃。
 */
@Service
@ConditionalOnProperty(prefix = "rag.rocketmq", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "rag.rocketmq", name = {"endpoints"})
@ConditionalOnProperty(prefix = "rag.catalog", name = "rocket-mq-topic")
@RocketMQMessageListener(
        endpoints = "${rag.rocketmq.endpoints}",
        topic = "${rag.catalog.rocket-mq-topic:catalog-attribute-extract}",
        tag = "${rag.catalog.rocket-mq-tag:attribute-extract}",
        consumerGroup = "${rag.catalog.consumer-group:catalog-attribute-consumer}"
)
public class CatalogAttributeMessageListener implements RocketMQListener {

    private static final Logger log = LoggerFactory.getLogger(CatalogAttributeMessageListener.class);

    private final RagJsonCodec jsonCodec;
    private final RagProperties ragProperties;
    private final CatalogProductRepository productRepository;
    private final LlmAttributeExtractor llmAttributeExtractor;

    public CatalogAttributeMessageListener(
            RagJsonCodec jsonCodec,
            RagProperties ragProperties,
            CatalogProductRepository productRepository,
            LlmAttributeExtractor llmAttributeExtractor
    ) {
        this.jsonCodec = jsonCodec;
        this.ragProperties = ragProperties;
        this.productRepository = productRepository;
        this.llmAttributeExtractor = llmAttributeExtractor;
    }

    @Override
    public ConsumeResult consume(MessageView messageView) {
        if (!ragProperties.catalog().enabled()) {
            log.debug("catalog attribute consumption skipped because rag.catalog.enabled=false");
            return ConsumeResult.SUCCESS;
        }

        CatalogAttributeMessagePayload payload;
        try {
            payload = parsePayload(messageView);
        } catch (RuntimeException exception) {
            // 解析失败的消息无意义重投递（重投也仍会失败），直接 ack 跳过。
            log.error(
                    "catalog attribute message parse failed: messageId={}, reason={}",
                    messageView.getMessageId(),
                    RagLogHelper.errorSummary(exception),
                    exception
            );
            return ConsumeResult.SUCCESS;
        }

        Long productId = payload.productId();
        if (!productRepository.markAttributeExtractionRunning(productId)) {
            log.debug("catalog attribute extraction skipped because state not eligible: productId={}", productId);
            return ConsumeResult.SUCCESS;
        }

        CatalogProductRecord product = productRepository.findById(productId).orElse(null);
        if (product == null) {
            productRepository.markAttributeExtractionFailed(productId, "product_not_found");
            log.warn("catalog attribute extraction aborted because Product disappeared: productId={}", productId);
            return ConsumeResult.SUCCESS;
        }

        try {
            Map<String, Object> attributes = llmAttributeExtractor.extract(product.title() + "\n" + product.rawJson());
            productRepository.markAttributeExtractionSucceeded(productId, attributes);
            log.info(
                    "catalog attribute extraction succeeded: productId={}, triggeredBy={}, attributesSize={}",
                    productId,
                    payload.triggeredBy(),
                    attributes.size()
            );
            return ConsumeResult.SUCCESS;
        } catch (RuntimeException exception) {
            String reason = RagLogHelper.errorSummary(exception);
            productRepository.markAttributeExtractionFailed(productId, reason);
            log.warn(
                    "catalog attribute extraction failed: productId={}, triggeredBy={}, reason={}",
                    productId,
                    payload.triggeredBy(),
                    reason
            );
            // 抛回 FAILURE 让 RocketMQ 重投递。耗尽次数后 catalog_spu 保留 FAILED 供人工 REST 重发。
            return ConsumeResult.FAILURE;
        }
    }

    private CatalogAttributeMessagePayload parsePayload(MessageView messageView) {
        ByteBuffer body = messageView.getBody().duplicate();
        byte[] bytes = new byte[body.remaining()];
        body.get(bytes);
        return jsonCodec.read(bytes, CatalogAttributeMessagePayload.class);
    }
}
