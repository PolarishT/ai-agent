package com.bytedance.ai.indexing.application;

import com.bytedance.ai.indexing.persistence.RagIndexOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成功消费后的严格 outbox 确认。
 *
 * <p>注意：这里不包裹 indexDocument() 本身，
 * 只负责在“索引已经成功完成”后做严格确认。
 * 若确认失败，抛异常给监听器，由 MQ 重试。
 */
@Service
public class RagIndexSuccessStateService {

    private final RagIndexOutboxRepository outboxRepository;

    public RagIndexSuccessStateService(RagIndexOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /**
     * 确认指定 outbox 事件已被消费者成功处理。
     *
     * <p>该方法只在索引已经成功完成后调用；确认失败会抛异常给 MQ listener，
     * 让消息消费按 RocketMQ 语义重试，避免“索引成功但本地消费确认丢失”。
     *
     * @param documentId    文档主键
     * @param contentSha256 文档内容 sha256
     * @param messageId     RocketMQ 消息 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirmConsumedOrThrow(Long documentId, String contentSha256, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalStateException(
                    "RAG outbox consumption confirmation failed because messageId is blank: documentId="
                            + documentId
            );
        }

        boolean confirmed = outboxRepository.confirmConsumed(documentId, contentSha256, messageId);
        if (!confirmed) {
            throw new IllegalStateException(
                    "RAG outbox consumption confirmation failed because no matching event was found: "
                            + "messageId=" + messageId
                            + ", documentId=" + documentId
            );
        }
    }
}
