package com.bytedance.ai.indexing.persistence.jdbc;

import com.bytedance.ai.indexing.model.RagIndexJobStatus;
import com.bytedance.ai.indexing.model.RagIndexStage;
import com.bytedance.ai.indexing.persistence.RagIndexJobRecord;
import com.bytedance.ai.indexing.persistence.RagIndexJobRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 基于 Spring JDBC 的离线索引作业仓储实现。
 */
@Repository
public class JdbcRagIndexJobRepository implements RagIndexJobRepository {

    private static final RowMapper<RagIndexJobRecord> ROW_MAPPER = (rs, _) -> new RagIndexJobRecord(
            rs.getLong("id"),
            rs.getLong("document_id"),
            rs.getString("content_sha256"),
            rs.getString("status"),
            rs.getString("stage"),
            rs.getLong("version"),
            rs.getString("last_event"),
            rs.getInt("attempt_count"),
            (Long) rs.getObject("target_generation"),
            rs.getString("message_id"),
            rs.getString("last_error"),
            toOffsetDateTime(rs.getTimestamp("started_at")),
            toOffsetDateTime(rs.getTimestamp("finished_at")),
            toOffsetDateTime(rs.getTimestamp("created_at")),
            toOffsetDateTime(rs.getTimestamp("updated_at"))
    );

    private final JdbcTemplate jdbc;

    public JdbcRagIndexJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }

    @Override
    public void queue(Long documentId, String contentSha256) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(queueSql(connection));
            statement.setLong(1, documentId);
            statement.setString(2, contentSha256);
            statement.setString(3, RagIndexJobStatus.QUEUED.name());
            statement.setString(4, RagIndexStage.QUEUED.name());
            return statement;
        });
    }

    @Override
    public void attachMessageId(Long documentId, String contentSha256, String messageId) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(attachMessageIdSql(connection));
            statement.setLong(1, documentId);
            statement.setString(2, contentSha256);
            statement.setString(3, messageId);
            if (isPostgreSql(connection)) {
                statement.setString(4, messageId);
            }
            return statement;
        });
    }

    @Override
    public void annotateEvent(Long documentId, String contentSha256, String event) {
        jdbc.update(
                """
                        UPDATE rag_index_jobs
                           SET last_event = ?, version = version + 1, updated_at = now()
                         WHERE document_id = ? AND content_sha256 = ?
                        """,
                event,
                documentId,
                contentSha256
        );
    }

    @Override
    public void recordRetry(Long documentId, String contentSha256, RagIndexStage stage, String errorMessage) {
        // MQ 将继续投递，所以 job 状态退回 QUEUED，但保留当前失败阶段和错误信息。
        jdbc.update(
                """
                        UPDATE rag_index_jobs
                           SET status = ?, stage = ?, last_error = ?, updated_at = now()
                         WHERE document_id = ? AND content_sha256 = ?
                        """,
                RagIndexJobStatus.QUEUED.name(),
                stage.name(),
                errorMessage,
                documentId,
                contentSha256
        );
    }

    @Override
    public Optional<RagIndexJobRecord> findByDocumentIdAndContentSha256(Long documentId, String contentSha256) {
        List<RagIndexJobRecord> results = jdbc.query(
                """
                            SELECT * FROM rag_index_jobs WHERE document_id = ? AND content_sha256 = ?
                        """,
                ROW_MAPPER,
                documentId,
                contentSha256
        );
        return results.stream().findFirst();
    }

    // ------------------------------------------------------------------------
    // CAS (Compare-And-Swap) 防并发写入防线
    // ------------------------------------------------------------------------

    @Override
    public void deleteByDocumentId(Long documentId) {
        jdbc.update(
                """
                           DELETE FROM rag_index_jobs WHERE document_id = ?
                        """, documentId);
    }

    @Override
    public List<RagIndexJobRecord> findStaleJobs(OffsetDateTime updatedBefore, int limit) {
        // 1. 转换 OffsetDateTime 为数据库识别的 Timestamp
        Timestamp timestamp = Timestamp.from(updatedBefore.toInstant());

        // 2. SQL 逻辑：
        // 筛选状态不是终态（SUCCEEDED, FAILED, SKIPPED）的任务。
        return jdbc.query(
                """
                        SELECT * FROM rag_index_jobs
                         WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'SKIPPED')
                           AND updated_at < ?
                         ORDER BY updated_at
                         LIMIT ?
                        """,
                ROW_MAPPER,
                timestamp,
                limit
        );
    }

    @Override
    public int updateStage(Long documentId, String contentSha256, RagIndexStage fromStage, RagIndexStage toStage) {
        return jdbc.update(
                """
                        UPDATE rag_index_jobs
                           SET stage = ?, updated_at = now()
                         WHERE document_id = ? AND content_sha256 = ? AND stage = ?
                        """,
                toStage.name(),
                documentId,
                contentSha256,
                fromStage.name()
        );
    }

    @Override
    public int startAttempt(Long documentId, String contentSha256, RagIndexStage fromStage, Long targetGeneration) {
        return jdbc.update(
                """
                        UPDATE rag_index_jobs
                           SET status = ?, stage = ?, target_generation = ?, attempt_count = attempt_count + 1,
                               last_error = NULL, started_at = COALESCE(started_at, now()), finished_at = NULL,
                               updated_at = now()
                         WHERE document_id = ? AND content_sha256 = ? AND stage = ?
                        """,
                RagIndexJobStatus.RUNNING.name(),
                RagIndexStage.PREPARING.name(),
                targetGeneration,
                documentId,
                contentSha256,
                fromStage.name()
        );
    }

    @Override
    public int markSucceeded(Long documentId, String contentSha256, RagIndexStage fromStage, Long targetGeneration) {
        return jdbc.update(
                """
                        UPDATE rag_index_jobs
                           SET status = ?, stage = ?, target_generation = ?, last_error = NULL,
                               finished_at = now(), updated_at = now()
                         WHERE document_id = ? AND content_sha256 = ? AND stage = ?
                        """,
                RagIndexJobStatus.SUCCEEDED.name(),
                RagIndexStage.COMPLETED.name(),
                targetGeneration,
                documentId,
                contentSha256,
                fromStage.name()
        );
    }

    @Override
    public int markFailed(Long documentId, String contentSha256, RagIndexStage fromStage, RagIndexStage failureStage, String errorMessage) {
        return jdbc.update(
                """
                        UPDATE rag_index_jobs
                           SET status = ?, stage = ?, last_error = ?, finished_at = now(), updated_at = now()
                         WHERE document_id = ? AND content_sha256 = ? AND stage = ?
                        """,
                RagIndexJobStatus.FAILED.name(),
                failureStage.name(),
                errorMessage,
                documentId,
                contentSha256,
                fromStage.name()
        );
    }

    @Override
    public int markSkipped(Long documentId, String contentSha256, RagIndexStage fromStage, String reason) {
        return jdbc.update(
                """
                        UPDATE rag_index_jobs
                           SET status = ?, stage = ?, last_error = ?, finished_at = now(), updated_at = now()
                         WHERE document_id = ? AND content_sha256 = ? AND stage = ?
                        """,
                RagIndexJobStatus.SKIPPED.name(),
                RagIndexStage.SKIPPED.name(),
                reason,
                documentId,
                contentSha256,
                fromStage.name()
        );
    }

    private String queueSql(Connection connection) throws java.sql.SQLException {
        if (isPostgreSql(connection)) {
            // Production PostgreSQL keeps the single-statement upsert for concurrent dispatch.
            return """
                    INSERT INTO rag_index_jobs (
                        document_id, content_sha256, status, stage
                    ) VALUES (?, ?, ?, ?)
                    ON CONFLICT (document_id, content_sha256)
                    DO UPDATE SET
                        status = EXCLUDED.status,
                        stage = EXCLUDED.stage,
                        attempt_count = 0,
                        target_generation = NULL,
                        message_id = NULL,
                        last_error = NULL,
                        started_at = NULL,
                        finished_at = NULL,
                        updated_at = now()
                    """;
        }
        return """
                MERGE INTO rag_index_jobs (
                    document_id, content_sha256, status, stage, attempt_count,
                    target_generation, message_id, last_error, started_at, finished_at, updated_at
                ) KEY (document_id, content_sha256)
                VALUES (?, ?, ?, ?, 0, NULL, NULL, NULL, NULL, NULL, CURRENT_TIMESTAMP)
                """;
    }

    private String attachMessageIdSql(Connection connection) throws java.sql.SQLException {
        if (isPostgreSql(connection)) {
            return """
                    INSERT INTO rag_index_jobs (
                        document_id, content_sha256, status, stage, message_id
                    ) VALUES (?, ?, 'QUEUED', 'QUEUED', ?)
                    ON CONFLICT (document_id, content_sha256)
                    DO UPDATE SET
                        message_id = ?,
                        updated_at = now()
                    """;
        }
        return """
                MERGE INTO rag_index_jobs (
                    document_id, content_sha256, status, stage, message_id, updated_at
                ) KEY (document_id, content_sha256)
                VALUES (?, ?, 'QUEUED', 'QUEUED', ?, CURRENT_TIMESTAMP)
                """;
    }

    private boolean isPostgreSql(Connection connection) throws java.sql.SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
    }
}
