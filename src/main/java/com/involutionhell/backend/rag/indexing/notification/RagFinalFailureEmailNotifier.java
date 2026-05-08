package com.involutionhell.backend.rag.indexing.notification;

import com.involutionhell.backend.rag.document.spi.DocumentIndexingSpi;
import com.involutionhell.backend.rag.document.spi.DocumentIndexingView;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 修正版：当离线索引最终失败时，向作者发送美化后的通知邮件。
 */
@Service
@ConditionalOnBean(JavaMailSender.class)
@ConditionalOnProperty(prefix = "rag.notification.final-failure-email", name = "enabled", havingValue = "true")
public class RagFinalFailureEmailNotifier {

    private static final Logger log = LoggerFactory.getLogger(RagFinalFailureEmailNotifier.class);

    private static final List<String> EMAIL_KEYS = List.of(
            "authorEmail", "author_email", "notifyEmail", "notify_email", "notificationEmail", "notification_email", "email"
    );

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT);

    private final DocumentIndexingSpi documentIndexingSpi;
    private final JavaMailSender mailSender;
    private final String from;
    private final String subjectPrefix;
    private final String baseUrl;

    public RagFinalFailureEmailNotifier(
            DocumentIndexingSpi documentIndexingSpi,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${rag.notification.final-failure-email.from:}") String from,
            @Value("${rag.notification.final-failure-email.subject-prefix:[RAG]}") String subjectPrefix,
            @Value("${rag.notification.final-failure-email.base-url:}") String baseUrl
    ) {
        this.documentIndexingSpi = documentIndexingSpi;
        this.mailSender = Objects.requireNonNull(
                mailSenderProvider.getIfAvailable(),
                "JavaMailSender bean is required when final failure email notifier is enabled"
        );
        this.from = from;
        this.subjectPrefix = subjectPrefix;
        this.baseUrl = baseUrl;
    }

    public void notifyFinalFailure(
            Long documentId,
            String contentSha256,
            String errorMessage,
            int maxAttempts,
            String messageId
    ) {
        DocumentIndexingView document = documentIndexingSpi.findById(documentId).orElse(null);
        if (document == null) {
            log.warn("Skip final failure email because document is missing: documentId={}", documentId);
            return;
        }

        // 版本校验：如果 SHA256 不匹配，说明文档已被更新，不再为旧版本发告警
        if (StringUtils.hasText(contentSha256)
                && StringUtils.hasText(document.contentSha256())
                && !contentSha256.equals(document.contentSha256())) {
            log.info("Skip final failure email because document version has changed: documentId={}", documentId);
            return;
        }

        String recipient = resolveAuthorEmail(document.metadata());
        if (!StringUtils.hasText(recipient)) {
            log.info("No recipient email found for documentId={}", documentId);
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            // 使用 multipart 模式以支持 HTML 和 Plain Text 共存
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

            if (StringUtils.hasText(from)) {
                helper.setFrom(from);
            }
            helper.setTo(recipient);
            helper.setSubject(buildSubject(document));

            // 获取两种版本的正文
            String plainText = buildPlainTextBody(document, errorMessage, maxAttempts, messageId);
            String htmlContent = buildHtmlBody(document, errorMessage, maxAttempts, messageId);

            // 同时设置纯文本和 HTML
            helper.setText(plainText, htmlContent);

            mailSender.send(message);
            log.info("Final failure notification sent to {} for documentId={}", recipient, documentId);
        } catch (Exception exception) {
            log.error("Failed to send final failure email for documentId={}", documentId, exception);
        }
    }

    private String buildSubject(DocumentIndexingView document) {
        String prefix = StringUtils.hasText(subjectPrefix) ? subjectPrefix.trim() + " " : "";
        String title = defaultString(document.title(), "未命名文档");
        return prefix + "索引任务最终失败: " + title;
    }

    private String buildPlainTextBody(DocumentIndexingView document, String errorMessage, int maxAttempts, String messageId) {
        return "RAG 文档索引最终失败通知\n" +
                "==============================\n" +
                "文档标题: " + defaultString(document.title(), "未命名文档") + "\n" +
                "最终错误: " + errorMessage + "\n" +
                "尝试次数: " + maxAttempts + "\n" +
                "更新时间: " + formatTimestamp(document.updatedAt()) + "\n" +
                "排查链接: " + buildTimelineUrl(document) + "\n\n" +
                "此为系统自动发送，请勿回复。";
    }

    private String buildHtmlBody(DocumentIndexingView document, String errorMessage, int maxAttempts, String messageId) {
        // 构建表格展示的详细信息
        List<RagEmailTemplates.Field> fields = List.of(
                new RagEmailTemplates.Field("文档 ID", String.valueOf(document.id()), false),
                new RagEmailTemplates.Field("文档标题", defaultString(document.title(), "未命名文档"), false),
                new RagEmailTemplates.Field("来源 URI", defaultString(document.sourceUri(), "-"), false),
                new RagEmailTemplates.Field("内容版本 (SHA)", RagLogHelper.shortSha(document.contentSha256()), false),
                new RagEmailTemplates.Field("最大尝试次数", String.valueOf(maxAttempts), false),
                new RagEmailTemplates.Field("最终错误信息", errorMessage, true), // 错误信息通常较长，设为 multiline
                new RagEmailTemplates.Field("最近更新时间", formatTimestamp(document.updatedAt()), false)
        );

        List<String> suggestions = List.of(
                "请根据上方排查链接查看完整的时间线轨迹",
                "核对文档元数据是否包含不可解析的特殊字符",
                "确认 Embedding 模型服务或向量数据库当前是否可用",
                "修正问题后，请在管理后台重新触发该文档的重建索引任务"
        );

        return RagEmailTemplates.finalFailureHtml(
                "文档索引最终失败告警",
                "您提交的 RAG 文档在离线索引链路中经过多次自动重试后仍未成功入库，已停止处理。",
                fields,
                buildTimelineUrl(document),
                suggestions
        );
    }

    // --- 工具方法保持逻辑不变 ---

    private String resolveAuthorEmail(Map<String, Object> metadata) {
        if (metadata == null) return null;
        String directEmail = firstEmail(metadata);
        if (StringUtils.hasText(directEmail)) return directEmail;

        Object authorObj = metadata.get("author");
        if (authorObj instanceof Map) {
            return firstEmail(asMap(authorObj));
        }

        Object frontmatterObj = metadata.get("frontmatter");
        if (frontmatterObj instanceof Map) {
            return firstEmail(asMap(frontmatterObj));
        }
        return null;
    }

    private String firstEmail(Map<String, Object> source) {
        for (String key : EMAIL_KEYS) {
            Object val = source.get(key);
            if (val != null) {
                String email = String.valueOf(val).trim();
                if (StringUtils.hasText(email)) return email;
            }
        }
        return null;
    }

    private Map<String, Object> asMap(Object value) {
        return (value instanceof Map) ? (Map<String, Object>) value : Map.of();
    }

    private String buildTimelineUrl(DocumentIndexingView document) {
        if (!StringUtils.hasText(baseUrl)) return null;
        return baseUrl.replaceAll("/+$", "") + "/public/rag/documents/index-timeline/" + document.id();
    }

    private String defaultString(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private String formatTimestamp(OffsetDateTime value) {
        if (value == null) return "-";
        return value.atZoneSameInstant(ZoneId.systemDefault()).format(TIMESTAMP_FORMATTER);
    }
}