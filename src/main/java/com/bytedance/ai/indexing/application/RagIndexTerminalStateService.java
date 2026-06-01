package com.bytedance.ai.indexing.application;

import com.bytedance.ai.indexing.persistence.RagIndexOutboxRepository;
import com.bytedance.ai.indexing.workflow.IndexWorkflowCommand;
import com.bytedance.ai.indexing.workflow.IndexWorkflowService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 负责将索引终态写入与 outbox 消费确认收敛到同一个本地事务中。
 *
 * <p>前提：{@link IndexWorkflowService#fail} / {@link IndexWorkflowService#skip}
 * 使用默认传播级别 REQUIRED，会加入本方法开启的外层事务。
 * 若其传播改为 REQUIRES_NEW，原子性保证将失效。
 */
@Service
public class RagIndexTerminalStateService {

    private final IndexWorkflowService workflowService;
    private final RagIndexOutboxRepository outboxRepository;

    public RagIndexTerminalStateService(
            IndexWorkflowService workflowService,
            RagIndexOutboxRepository outboxRepository
    ) {
        this.workflowService = workflowService;
        this.outboxRepository = outboxRepository;
    }

    /**
     * 将索引任务推进到失败终态，并在同一事务中确认对应 outbox 已消费。
     *
     * @param command 携带文档版本、消息 ID 和失败上下文的工作流命令
     */
    @Transactional(rollbackFor = Exception.class)
    public void failAndConfirmConsumed(IndexWorkflowCommand command) {
        workflowService.fail(command);
        confirmConsumed(command);
    }

    /**
     * 将索引任务推进到跳过终态，并在同一事务中确认对应 outbox 已消费。
     *
     * @param command 携带文档版本、消息 ID 和跳过原因的工作流命令
     */
    @Transactional(rollbackFor = Exception.class)
    public void skipAndConfirmConsumed(IndexWorkflowCommand command) {
        workflowService.skip(command);
        confirmConsumed(command);
    }

    private void confirmConsumed(IndexWorkflowCommand command) {
        if (command.messageId() == null || command.messageId().isBlank()) {
            throw new IllegalStateException(
                    "RAG outbox consumption confirmation failed because messageId is blank: documentId="
                            + command.documentId()
            );
        }

        boolean confirmed = outboxRepository.confirmConsumed(
                command.documentId(),
                command.contentSha256(),
                command.messageId()
        );

        if (!confirmed) {
            throw new IllegalStateException(
                    "RAG outbox consumption confirmation failed because no matching event was found: "
                            + "messageId=" + command.messageId()
                            + ", documentId=" + command.documentId()
            );
        }
    }
}
