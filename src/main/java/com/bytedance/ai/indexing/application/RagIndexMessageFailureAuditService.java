package com.bytedance.ai.indexing.application;

import com.bytedance.ai.indexing.persistence.RagIndexMessageFailureRepository;
import com.bytedance.ai.indexing.service.RagIndexingMetrics;
import com.bytedance.ai.shared.properties.RagProperties;
import com.bytedance.ai.shared.support.RagJsonCodec;
import com.bytedance.ai.shared.support.RagLogHelper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.stereotype.Service;

/**
 * 审计离线索引消息失败。
 *
 * <p>该服务处理无法进入正常索引工作流的消息异常，尤其是 RocketMQ 消息反序列化失败。
 * 它会保存原始 payload 摘要、消息属性和失败次数，并给告警逻辑提供阈值判断结果。
 */
@Service
public class RagIndexMessageFailureAuditService {

    private final RagIndexMessageFailureRepository failureRepository;
    private final RagIndexingMetrics indexingMetrics;
    private final RagProperties ragProperties;
    private final RagJsonCodec jsonCodec;

    public RagIndexMessageFailureAuditService(
            RagIndexMessageFailureRepository failureRepository,
            RagIndexingMetrics indexingMetrics,
            RagProperties ragProperties,
            RagJsonCodec jsonCodec
    ) {
        this.failureRepository = failureRepository;
        this.indexingMetrics = indexingMetrics;
        this.ragProperties = ragProperties;
        this.jsonCodec = jsonCodec;
    }

    /**
     * 记录一次消息解析失败并返回告警判断结果。
     *
     * @param messageView RocketMQ 消息视图
     * @param payload     原始消息体
     * @param exception   解析异常
     * @return 解析失败审计结果
     */
    public ParseFailureAuditResult recordParseFailure(MessageView messageView, byte[] payload, Exception exception) {
        String messageId = String.valueOf(messageView.getMessageId());
        String failureType = "parse";
        String errorMessage = abbreviate(exception.getMessage(), 500);
        String payloadBase64 = payload == null ? null : Base64.getEncoder().encodeToString(payload);
        String payloadPreview = payload == null
                ? null
                : abbreviate(new String(payload, StandardCharsets.UTF_8), ragProperties.rocketMq().parseFailurePayloadPreviewLength());
        Map<String, Object> properties = toProperties(messageView);
        String propertiesJson = jsonCodec.write(properties);

        failureRepository.save(
                messageId,
                messageView.getTopic(),
                normalizeAttempt(messageView.getDeliveryAttempt()),
                failureType,
                errorMessage,
                payloadBase64,
                payloadPreview,
                properties
        );

        int failureCount = failureRepository.countByMessageId(messageId);
        int threshold = ragProperties.rocketMq().parseFailureAlertThreshold();
        boolean thresholdReached = failureCount >= threshold;
        boolean alertRecommended = failureCount == threshold;
        indexingMetrics.recordMessageParseFailure(thresholdReached);
        return new ParseFailureAuditResult(messageId, failureCount, thresholdReached, alertRecommended, payloadPreview, propertiesJson, errorMessage);
    }

    private Map<String, Object> toProperties(MessageView messageView) {
        try {
            return jsonCodec.convertToMap(messageView.getProperties());
        } catch (Exception exception) {
            return Map.of("serializationError", abbreviate(exception.getMessage(), 120));
        }
    }

    private int normalizeAttempt(int deliveryAttempt) {
        return deliveryAttempt <= 0 ? 1 : deliveryAttempt;
    }

    private String abbreviate(String value, int maxLength) {
        return RagLogHelper.abbreviate(value, maxLength);
    }

    public record ParseFailureAuditResult(
            String messageId,
            int failureCount,
            boolean thresholdReached,
            boolean alertRecommended,
            String payloadPreview,
            String propertiesJson,
            String errorMessage
    ) {
    }
}
