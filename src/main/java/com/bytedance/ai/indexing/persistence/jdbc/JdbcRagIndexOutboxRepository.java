package com.bytedance.ai.indexing.persistence.jdbc;

import com.bytedance.ai.indexing.persistence.RagIndexOutboxRepository;
import com.bytedance.ai.indexing.persistence.RagIndexOutboxRecord;
import com.bytedance.ai.indexing.model.RagIndexOutboxEventType;
import com.bytedance.ai.indexing.model.RagIndexOutboxStatus;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 基于 Spring JDBC 的索引 Outbox 仓储实现。
 */
@Repository
public class JdbcRagIndexOutboxRepository implements RagIndexOutboxRepository {

    private static final RowMapper<RagIndexOutboxRecord> ROW_MAPPER = (rs, rowNum) -> new RagIndexOutboxRecord(
            rs.getLong("id"),
            rs.getLong("document_id"),
            rs.getString("content_sha256"),
            rs.getString("event_type"),
            rs.getString("status"),
            rs.getInt("attempt_count"),
            rs.getString("message_id"),
            rs.getString("last_error"),
            toOffsetDateTime(rs.getTimestamp("next_attempt_at")),
            toOffsetDateTime(rs.getTimestamp("dispatched_at")),
            toOffsetDateTime(rs.getTimestamp("consumed_at")),
            toOffsetDateTime(rs.getTimestamp("created_at")),
            toOffsetDateTime(rs.getTimestamp("updated_at"))
    );

    private final JdbcTemplate jdbc;

    public JdbcRagIndexOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enqueue(Long documentId, String contentSha256, RagIndexOutboxEventType eventType) {
        // 同一文档版本重复触发时复用既有 Outbox 行，把它重置为待发送。
        int updatedRows = jdbc.update(
                """
                UPDATE rag_index_outbox
                   SET status = ?, attempt_count = 0, message_id = NULL, last_error = NULL,
                       next_attempt_at = now(), dispatched_at = NULL, consumed_at = NULL, updated_at = now()
                 WHERE document_id = ? AND content_sha256 = ? AND event_type = ?
                """,
                RagIndexOutboxStatus.NEW.name(),
                documentId,
                contentSha256,
                eventType.name()
        );
        if (updatedRows > 0) {
            return;
        }

        try {
            jdbc.update(
                    """
                    INSERT INTO rag_index_outbox (
                        document_id, content_sha256, event_type, status, message_id, next_attempt_at, consumed_at
                    ) VALUES (?, ?, ?, ?, NULL, now(), NULL)
                    """,
                    documentId,
                    contentSha256,
                    eventType.name(),
                    RagIndexOutboxStatus.NEW.name()
            );
        } catch (DuplicateKeyException exception) {
            // 并发 enqueue 时最终仍收敛到同一条事件记录。
            enqueue(documentId, contentSha256, eventType);
        }
    }

    @Override
    public List<RagIndexOutboxRecord> findDispatchable(OffsetDateTime now, int limit) {
        return jdbc.query(
                """
                SELECT * FROM rag_index_outbox
                 WHERE status IN (?, ?)
                   AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                 ORDER BY created_at ASC
                 LIMIT ?
                """,
                ROW_MAPPER,
                RagIndexOutboxStatus.NEW.name(),
                RagIndexOutboxStatus.FAILED.name(),
                Timestamp.from(now.toInstant()),
                limit
        );
    }

    @Override
    public boolean markSending(Long id) {
        // 用状态机占位发送权，避免多个 dispatcher 同时重复投递同一事件。
        int updatedRows = jdbc.update(
                """
                UPDATE rag_index_outbox
                   SET status = ?, updated_at = now()
                 WHERE id = ? AND status IN (?, ?)
                """,
                RagIndexOutboxStatus.SENDING.name(),
                id,
                RagIndexOutboxStatus.NEW.name(),
                RagIndexOutboxStatus.FAILED.name()
        );
        return updatedRows > 0;
    }

    @Override
    public void markSent(Long id, String messageId) {
        jdbc.update(
                """
                UPDATE rag_index_outbox
                   SET status = ?, message_id = ?, last_error = NULL, dispatched_at = now(), updated_at = now()
                 WHERE id = ?
                """,
                RagIndexOutboxStatus.SENT.name(),
                messageId,
                id
        );
    }

    @Override
    public boolean confirmConsumed(Long documentId, String contentSha256, String messageId) {
        int updatedRows = jdbc.update(
                """
                UPDATE rag_index_outbox
                   SET status = ?, message_id = COALESCE(message_id, ?), last_error = NULL,
                       dispatched_at = COALESCE(dispatched_at, now()),
                       consumed_at = COALESCE(consumed_at, now()),
                       updated_at = now()
                 WHERE document_id = ? AND content_sha256 = ? AND event_type = ?
                   AND (message_id IS NULL OR message_id = ?)
                """,
                RagIndexOutboxStatus.SENT.name(),
                messageId,
                documentId,
                contentSha256,
                RagIndexOutboxEventType.INDEX_DOCUMENT.name(),
                messageId
        );
        return updatedRows > 0;
    }

    @Override
    public boolean confirmConsumedByMessageId(String messageId) {
        int updatedRows = jdbc.update(
                """
                UPDATE rag_index_outbox
                   SET status = ?, last_error = NULL,
                       dispatched_at = COALESCE(dispatched_at, now()),
                       consumed_at = COALESCE(consumed_at, now()),
                       updated_at = now()
                 WHERE message_id = ?
                """,
                RagIndexOutboxStatus.SENT.name(),
                messageId
        );
        return updatedRows > 0;
    }

    @Override
    public void markFailed(Long id, String errorMessage, OffsetDateTime nextAttemptAt) {
        // 失败时增加 attempt_count，并把下一次可发送时间交给调度器判断。
        jdbc.update(
                """
                UPDATE rag_index_outbox
                   SET status = ?, attempt_count = attempt_count + 1, last_error = ?,
                       next_attempt_at = ?, updated_at = now()
                 WHERE id = ?
                """,
                RagIndexOutboxStatus.FAILED.name(),
                errorMessage,
                Timestamp.from(nextAttemptAt.toInstant()),
                id
        );
    }

    @Override
    public List<RagIndexOutboxRecord> findStuckSendingBefore(OffsetDateTime cutoff, int limit) {
        return jdbc.query(
                """
                SELECT * FROM rag_index_outbox
                 WHERE status = ?
                   AND updated_at <= ?
                 ORDER BY updated_at ASC
                 LIMIT ?
                """,
                ROW_MAPPER,
                RagIndexOutboxStatus.SENDING.name(),
                Timestamp.from(cutoff.toInstant()),
                limit
        );
    }

    @Override
    public void resetForRetry(Long id, String errorMessage, OffsetDateTime nextAttemptAt) {
        // stuck sending 的补偿不会重复累加 attempt_count，只是把事件重新放回失败待重试。
        jdbc.update(
                """
                UPDATE rag_index_outbox
                   SET status = ?, last_error = ?, next_attempt_at = ?, updated_at = now()
                 WHERE id = ?
                """,
                RagIndexOutboxStatus.FAILED.name(),
                errorMessage,
                Timestamp.from(nextAttemptAt.toInstant()),
                id
        );
    }

    @Override
    public Optional<RagIndexOutboxRecord> findByDocumentIdAndContentSha256(Long documentId, String contentSha256) {
        List<RagIndexOutboxRecord> results = jdbc.query(
                """
                SELECT * FROM rag_index_outbox
                 WHERE document_id = ? AND content_sha256 = ?
                 ORDER BY updated_at DESC
                 LIMIT 1
                """,
                ROW_MAPPER,
                documentId,
                contentSha256
        );
        return results.stream().findFirst();
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        jdbc.update("DELETE FROM rag_index_outbox WHERE document_id = ?", documentId);
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
