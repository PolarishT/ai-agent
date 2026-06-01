package com.bytedance.ai.indexing.persistence.jdbc;

import com.bytedance.ai.indexing.persistence.RagIndexJobTransitionRepository;
import com.bytedance.ai.indexing.persistence.RagIndexJobTransitionRecord;
import com.bytedance.ai.shared.support.RagJsonCodec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 基于 Spring JDBC 的索引状态转移审计仓储实现。
 */
@Repository
public class JdbcRagIndexJobTransitionRepository implements RagIndexJobTransitionRepository {

    private final JdbcTemplate jdbc;
    private final RagJsonCodec jsonCodec;

    public JdbcRagIndexJobTransitionRepository(JdbcTemplate jdbc, RagJsonCodec jsonCodec) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public void save(
            Long documentId,
            Long jobId,
            Long outboxId,
            String contentSha256,
            String fromState,
            String toState,
            String event,
            String triggerType,
            String triggeredBy,
            boolean success,
            String failureReason,
            String errorMessage,
            String messageId,
            Map<String, Object> metadataJson
    ) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(insertSql(connection));
            statement.setLong(1, documentId);
            if (jobId == null) {
                statement.setNull(2, Types.BIGINT);
            } else {
                statement.setLong(2, jobId);
            }
            if (outboxId == null) {
                statement.setNull(3, Types.BIGINT);
            } else {
                statement.setLong(3, outboxId);
            }
            statement.setString(4, contentSha256);
            statement.setString(5, fromState);
            statement.setString(6, toState);
            statement.setString(7, event);
            statement.setString(8, triggerType);
            statement.setString(9, triggeredBy);
            statement.setBoolean(10, success);
            statement.setString(11, failureReason);
            statement.setString(12, errorMessage);
            statement.setString(13, messageId);
            bindMetadata(statement, connection, 14, metadataJson);
            return statement;
        });
    }

    @Override
    public List<RagIndexJobTransitionRecord> findByDocumentIdAndContentSha256(Long documentId, String contentSha256) {
        return jdbc.query(
                """
                SELECT *
                  FROM rag_index_job_transitions
                 WHERE document_id = ? AND content_sha256 = ?
                 ORDER BY created_at ASC, id ASC
                """,
                rowMapper(),
                documentId,
                contentSha256
        );
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        jdbc.update("DELETE FROM rag_index_job_transitions WHERE document_id = ?", documentId);
    }

    private String insertSql(Connection connection) throws java.sql.SQLException {
        if (isPostgreSql(connection)) {
            return """
                    INSERT INTO rag_index_job_transitions (
                        document_id, job_id, outbox_id, content_sha256, from_state, to_state, event,
                        trigger_type, triggered_by, success, failure_reason, error_message, message_id, metadata
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """;
        }
        return """
                INSERT INTO rag_index_job_transitions (
                    document_id, job_id, outbox_id, content_sha256, from_state, to_state, event,
                    trigger_type, triggered_by, success, failure_reason, error_message, message_id, metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
    }

    private void bindMetadata(PreparedStatement statement, Connection connection, int parameterIndex, Map<String, Object> metadataJson)
            throws java.sql.SQLException {
        if (metadataJson == null) {
            statement.setNull(parameterIndex, Types.VARCHAR);
            return;
        }
        if (isPostgreSql(connection)) {
            statement.setString(parameterIndex, jsonCodec.write(metadataJson));
            return;
        }
        statement.setObject(parameterIndex, jsonCodec.write(metadataJson));
    }

    private RowMapper<RagIndexJobTransitionRecord> rowMapper() {
        return (rs, rowNum) -> new RagIndexJobTransitionRecord(
                rs.getLong("id"),
                rs.getLong("document_id"),
                (Long) rs.getObject("job_id"),
                (Long) rs.getObject("outbox_id"),
                rs.getString("content_sha256"),
                rs.getString("from_state"),
                rs.getString("to_state"),
                rs.getString("event"),
                rs.getString("trigger_type"),
                rs.getString("triggered_by"),
                rs.getBoolean("success"),
                rs.getString("failure_reason"),
                rs.getString("error_message"),
                rs.getString("message_id"),
                jsonCodec.readMap(rs.getString("metadata")),
                toOffsetDateTime(rs.getTimestamp("created_at"))
        );
    }

    private boolean isPostgreSql(Connection connection) throws java.sql.SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
