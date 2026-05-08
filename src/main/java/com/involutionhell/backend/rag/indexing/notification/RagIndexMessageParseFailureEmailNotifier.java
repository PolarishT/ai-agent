package com.involutionhell.backend.rag.indexing.notification;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 修正版：RocketMQ 索引消息解析失败邮件告警服务
 */
@Service
@ConditionalOnBean(JavaMailSender.class)
@ConditionalOnProperty(prefix = "rag.notification.message-parse-failure-email", name = "enabled", havingValue = "true")
public class RagIndexMessageParseFailureEmailNotifier implements RagIndexMessageParseFailureNotifier {

    private static final Logger log = LoggerFactory.getLogger(RagIndexMessageParseFailureEmailNotifier.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT);

    private final JavaMailSender mailSender;
    private final String from;
    private final String recipients;
    private final String subjectPrefix;

    public RagIndexMessageParseFailureEmailNotifier(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${rag.notification.message-parse-failure-email.from:}") String from,
            @Value("${rag.notification.message-parse-failure-email.recipients:}") String recipients,
            @Value("${rag.notification.message-parse-failure-email.subject-prefix:[RAG]}") String subjectPrefix
    ) {
        this.mailSender = Objects.requireNonNull(
                mailSenderProvider.getIfAvailable(),
                "JavaMailSender bean is required when parse failure email notifier is enabled"
        );
        this.from = from;
        this.recipients = recipients;
        this.subjectPrefix = subjectPrefix;
    }

    @Override
    public void notifyParseFailureThresholdReached(
            String messageId,
            String topic,
            int deliveryAttempt,
            int failureCount,
            String errorMessage,
            String payloadPreview,
            String propertiesJson
    ) {
        String[] resolvedRecipients = Arrays.stream(recipients.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toArray(String[]::new);

        if (resolvedRecipients.length == 0) {
            log.warn("Skip alert email: no recipients configured for messageId={}", messageId);
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            // 生产环境建议：multipart 设为 true 以支持附件或内嵌资源，虽然目前只用 HTML
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

            if (StringUtils.hasText(from)) {
                helper.setFrom(from);
            }
            helper.setTo(resolvedRecipients);
            helper.setSubject(buildSubject(topic));

            // 生成两个版本的正文：纯文本（Text）和 样式（HTML）
            String plainText = buildPlainTextBody(messageId, topic, deliveryAttempt, failureCount, errorMessage, payloadPreview, propertiesJson);
            String htmlContent = buildHtmlBody(messageId, topic, deliveryAttempt, failureCount, errorMessage, payloadPreview, propertiesJson);

            // 设置邮件内容，第二个参数 true 表示这是 HTML
            helper.setText(plainText, htmlContent);

            mailSender.send(message);
            log.warn("RAG alert email sent successfully to {} recipients for topic={}", resolvedRecipients.length, topic);
        } catch (Exception exception) {
            log.error("Failed to send RAG parse failure email for messageId={}", messageId, exception);
        }
    }

    private String buildSubject(String topic) {
        String prefix = StringUtils.hasText(subjectPrefix) ? subjectPrefix.trim() + " " : "";
        return prefix + "索引消息解析失败告警: " + (StringUtils.hasText(topic) ? topic : "未知队列");
    }

    /**
     * 构建纯文本版本（供不支持 HTML 的老旧客户端查看）
     */
    private String buildPlainTextBody(
            String messageId, String topic, int deliveryAttempt, int failureCount,
            String errorMessage, String payloadPreview, String propertiesJson
    ) {
        return "RocketMQ 索引消息解析失败告警\n" +
                "================================\n" +
                "Message ID: " + messageId + "\n" +
                "Topic: " + topic + "\n" +
                "投递次数: " + deliveryAttempt + "\n" +
                "错误详情: " + errorMessage + "\n" +
                "Payload 预览: " + payloadPreview + "\n" +
                "告警时间: " + getCurrentTimeStr();
    }

    /**
     * 调用模板渲染美化的 HTML 版本
     */
    private String buildHtmlBody(
            String messageId, String topic, int deliveryAttempt, int failureCount,
            String errorMessage, String payloadPreview, String propertiesJson
    ) {
        // 构造字段列表，注意 multiline 参数的使用
        List<RagEmailTemplates.Field> fields = List.of(
                new RagEmailTemplates.Field("Message ID", messageId, false),
                new RagEmailTemplates.Field("Topic", topic, false),
                new RagEmailTemplates.Field("投递/失败次数", deliveryAttempt + " / " + failureCount, false),
                new RagEmailTemplates.Field("最近错误", errorMessage, true),
                new RagEmailTemplates.Field("Payload 预览", payloadPreview, true),
                new RagEmailTemplates.Field("Properties JSON", propertiesJson, true),
                new RagEmailTemplates.Field("告警时间", getCurrentTimeStr(), false)
        );

        List<String> suggestions = List.of(
                "检查消息发布方与消费方的 Data Transfer Object (DTO) 是否版本一致",
                "排查是否存在因业务变更导致的脏数据或非法 JSON 格式",
                "若故障持续，请手动检查 RocketMQ 死信队列 (DLQ) 并根据 TraceID 追踪链路"
        );

        return RagEmailTemplates.parseFailureHtml(
                "消息解析异常告警",
                "检测到 RocketMQ 索引消息反序列化失败次数已超过设定阈值，系统已自动挂起该消息的进一步重试。",
                fields,
                suggestions
        );
    }

    private String getCurrentTimeStr() {
        return OffsetDateTime.now().atZoneSameInstant(ZoneId.systemDefault()).format(TIMESTAMP_FORMATTER);
    }
}