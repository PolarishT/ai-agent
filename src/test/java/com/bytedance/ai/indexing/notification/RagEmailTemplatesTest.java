package com.bytedance.ai.indexing.notification;

import com.bytedance.ai.document.spi.DocumentIndexingSpi;
import com.bytedance.ai.document.spi.DocumentIndexingView;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;


@SpringBootTest(properties = {
        "rag.notification.final-failure-email.enabled=true",
        "rag.notification.message-parse-failure-email.enabled=true",
        "spring.ai.model.audio.speech=none",
        "spring.ai.model.audio.transcription=none",
        "spring.ai.model.image=none",
        "spring.ai.model.moderation=none",
        // 强制指定发件配置，防止 @Value 注入失败
        "rag.notification.final-failure-email.from=zhangzhenting.lay@gmail.com",
        "rag.notification.final-failure-email.base-url=http://localhost:8080"
})
@Import({
        RagFinalFailureEmailNotifier.class,
        RagEmailTemplates.class,
        MailSenderAutoConfiguration.class
})
class RagEmailIntegrationTest {

    @Autowired
    private RagFinalFailureEmailNotifier notifier;

    @MockitoBean
    private DocumentIndexingSpi documentIndexingSpi;

    @MockitoBean
    private io.milvus.client.MilvusServiceClient milvusClient;

    @MockitoBean
    private org.springframework.ai.vectorstore.milvus.MilvusVectorStore milvusVectorStore;

    @MockitoBean
    private com.bytedance.ai.infrastructure.config.RagConfiguration ragConfiguration;
    @Test
    void sendTestEmail() {
        // 1. 准备模拟的文档数据
        // 这里的 key 必须包含之前代码里定义的 EMAIL_KEYS，如 "authorEmail"
        Map<String, Object> metadata = Map.of(
                "authorEmail", "zhangzhenting.lay@gmail.com",
                "department", "AI-Research"
        );

        DocumentIndexingView mockDocument = Mockito.mock(DocumentIndexingView.class);
        Mockito.when(mockDocument.id()).thenReturn(88888L);
        Mockito.when(mockDocument.title()).thenReturn("深度学习论文分析：Transformer 架构");
        Mockito.when(mockDocument.sourceUri()).thenReturn("s3://rag-buckets/papers/transformer.pdf");
        Mockito.when(mockDocument.contentSha256()).thenReturn("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        Mockito.when(mockDocument.metadata()).thenReturn(metadata);
        Mockito.when(mockDocument.updatedAt()).thenReturn(OffsetDateTime.now());

        // 2. 模拟数据库查询行为
        Mockito.when(documentIndexingSpi.findById(anyLong()))
                .thenReturn(Optional.of(mockDocument));

        // 3. 执行发送
        System.out.println("正在向 zhangzhenting.lay@gmail.com 发送测试邮件...");

        notifier.notifyFinalFailure(
                88888L,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "Vector Database Error: Unexpected status code 500 from Milvus. \nDetail: Collection 'rag_docs' is currently under maintenance or locked.",
                3,
                "RMQ-MSG-ID-TEST-123456"
        );

        System.out.println("测试方法执行完毕，请检查您的 Gmail 收件箱（包括垃圾邮件箱）。");
    }
}
