package com.bytedance.ai.document.persistence.jdbc;

import com.bytedance.ai.document.persistence.RagDocumentRecord;
import com.bytedance.ai.document.persistence.RagDocumentRepository;
import com.bytedance.ai.shared.model.RagDocumentStatus;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 Spring JDBC 的 RAG 文档仓储实现。
 */
@Repository
public class JdbcRagDocumentRepository implements RagDocumentRepository {

    private final JdbcTemplate jdbc;
    private final RagJsonCodec jsonCodec;

    public JdbcRagDocumentRepository(JdbcTemplate jdbc, RagJsonCodec jsonCodec) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }

    @Override
    @Transactional
    public RagDocumentRecord save(
            String sourceType,
            String sourceUri,
            String externalRef,
            String title,
            String content,
            String contentSha256,
            Map<String, Object> metadataJson
    ) {
        // 文档主记录是整个离线链路的起点，先落库成功再进入后续分发流程。
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(insertSql(connection), Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, sourceType);
            statement.setString(2, sourceUri);
            statement.setString(3, externalRef);
            statement.setString(4, title);
            statement.setString(5, content);
            statement.setString(6, contentSha256);
            statement.setString(7, RagDocumentStatus.PENDING.name());
            bindMetadata(statement, connection, 8, metadataJson);
            return statement;
        }, keyHolder);

        Number id = extractId(keyHolder);
        if (id == null) {
            throw new IllegalStateException("创建 RAG 文档失败，未返回主键");
        }
        return findById(id.longValue()).orElseThrow(() -> new IllegalStateException("创建 RAG 文档后查询失败"));
    }

    @Override
    @Transactional
    public RagDocumentRecord update(
            Long id,
            String sourceType,
            String sourceUri,
            String externalRef,
            String title,
            String content,
            String contentSha256,
            Map<String, Object> metadataJson
    ) {
        // 更新内容时会把状态重新置为 PENDING，确保后续重新切片和向量化。
        int updatedRows = jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(updateSql(connection));
            statement.setString(1, sourceType);
            statement.setString(2, sourceUri);
            statement.setString(3, externalRef);
            statement.setString(4, title);
            statement.setString(5, content);
            statement.setString(6, contentSha256);
            statement.setString(7, RagDocumentStatus.PENDING.name());
            bindMetadata(statement, connection, 8, metadataJson);
            statement.setLong(9, id);
            return statement;
        });
        if (updatedRows == 0) {
            throw new IllegalArgumentException("RAG 文档不存在: " + id);
        }
        return findById(id).orElseThrow(() -> new IllegalStateException("更新 RAG 文档后查询失败"));
    }

    @Override
    public Optional<RagDocumentRecord> findById(Long id) {
        List<RagDocumentRecord> results = jdbc.query(
                "SELECT * FROM rag_documents WHERE id = ?",
                rowMapper(),
                id
        );
        return results.stream().findFirst();
    }

    @Override
    public List<RagDocumentRecord> findPendingBefore(OffsetDateTime cutoff, int limit) {
        return jdbc.query(
                """
                        SELECT * FROM rag_documents
                         WHERE status = ?
                           AND updated_at <= ?
                         ORDER BY updated_at
                         LIMIT ?
                        """,
                rowMapper(),
                RagDocumentStatus.PENDING.name(),
                Timestamp.from(cutoff.toInstant()),
                limit
        );
    }

    @Override
    public List<RagDocumentRecord> findProcessingBefore(OffsetDateTime cutoff, int limit) {
        return jdbc.query(
                """
                        SELECT * FROM rag_documents
                         WHERE status = ?
                           AND COALESCE(last_attempted_at, updated_at) <= ?
                         ORDER BY COALESCE(last_attempted_at, updated_at)
                         LIMIT ?
                        """,
                rowMapper(),
                RagDocumentStatus.PROCESSING.name(),
                Timestamp.from(cutoff.toInstant()),
                limit
        );
    }

    @Override
    public List<RagDocumentRecord> findFailedBefore(OffsetDateTime cutoff, int limit) {
        return jdbc.query(
                """
                        SELECT * FROM rag_documents
                         WHERE status = ?
                           AND updated_at <= ?
                         ORDER BY updated_at
                         LIMIT ?
                        """,
                rowMapper(),
                RagDocumentStatus.FAILED.name(),
                Timestamp.from(cutoff.toInstant()),
                limit
        );
    }

    @Override
    public List<RagDocumentRecord> findDeletingBefore(OffsetDateTime cutoff, int limit) {
        return jdbc.query(
                """
                        SELECT * FROM rag_documents
                         WHERE status = ?
                           AND updated_at <= ?
                         ORDER BY updated_at
                         LIMIT ?
                        """,
                rowMapper(),
                RagDocumentStatus.DELETING.name(),
                Timestamp.from(cutoff.toInstant()),
                limit
        );
    }

    @Override
    @Transactional
    public void markPending(Long id) {
        // 重新排队时清空上次尝试痕迹，避免旧错误误导补偿逻辑。
        updateUnlessDeleting(
                """
                        UPDATE rag_documents
                           SET status = ?, attempt_count = 0, last_error = NULL,
                               last_attempted_at = NULL, updated_at = now()
                         WHERE id = ? AND status <> ?
                        """,
                id,
                RagDocumentStatus.PENDING.name(),
                id,
                RagDocumentStatus.DELETING.name()
        );
    }

    @Override
    @Transactional
    public void requeue(Long id, String note) {
        updateUnlessDeleting(
                """
                        UPDATE rag_documents
                           SET status = ?, last_error = ?, updated_at = now()
                         WHERE id = ? AND status <> ?
                        """,
                id,
                RagDocumentStatus.PENDING.name(),
                note,
                id,
                RagDocumentStatus.DELETING.name()
        );
    }

    @Override
    @Transactional
    public void markProcessing(Long id) {
        // 每次真正开始处理时才递增 attempt_count，便于统计真实索引尝试次数。
        updateUnlessDeleting(
                """
                        UPDATE rag_documents
                           SET status = ?, attempt_count = attempt_count + 1, last_error = NULL,
                               last_attempted_at = now(), updated_at = now()
                         WHERE id = ? AND status <> ?
                        """,
                id,
                RagDocumentStatus.PROCESSING.name(),
                id,
                RagDocumentStatus.DELETING.name()
        );
    }

    @Override
    @Transactional
    public void markIndexed(Long id, Long indexedGeneration, int chunkCount, OffsetDateTime indexedAt) {
        // 只有完整索引成功后，才切换当前生效 generation 并清空错误信息。
        updateUnlessDeleting(
                """
                        UPDATE rag_documents
                           SET status = ?, indexed_generation = ?, chunk_count = ?, indexed_at = ?, last_error = NULL, updated_at = now()
                         WHERE id = ? AND status <> ?
                        """,
                id,
                RagDocumentStatus.INDEXED.name(),
                indexedGeneration,
                chunkCount,
                Timestamp.from(indexedAt.toInstant()),
                id,
                RagDocumentStatus.DELETING.name()
        );
    }

    @Override
    @Transactional
    public void markFailed(Long id, String errorMessage) {
        updateUnlessDeleting(
                """
                        UPDATE rag_documents
                           SET status = ?, last_error = ?, updated_at = now()
                         WHERE id = ? AND status <> ?
                        """,
                id,
                RagDocumentStatus.FAILED.name(),
                errorMessage,
                id,
                RagDocumentStatus.DELETING.name()
        );
    }

    @Override
    @Transactional
    public void markDeleting(Long id, String note) {
        updateRequired(
                """
                        UPDATE rag_documents
                           SET status = ?, last_error = ?, updated_at = now()
                         WHERE id = ?
                        """,
                id,
                RagDocumentStatus.DELETING.name(),
                note,
                id
        );
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        // rag 模块显式不依赖数据库外键级联，因此物理删除时需要在事务内手动清理子表。
        jdbc.update("DELETE FROM rag_chunks WHERE document_id = ?", id);
        jdbc.update("DELETE FROM rag_index_outbox WHERE document_id = ?", id);
        jdbc.update("DELETE FROM rag_index_job_transitions WHERE document_id = ?", id);
        jdbc.update("DELETE FROM rag_index_jobs WHERE document_id = ?", id);
        int deletedRows = jdbc.update("DELETE FROM rag_documents WHERE id = ?", id);
        if (deletedRows == 0) {
            throw new IllegalArgumentException("RAG 文档不存在: " + id);
        }
    }

    private String insertSql(Connection connection) throws java.sql.SQLException {
        if (isPostgresSql(connection)) {
            return """
                    INSERT INTO rag_documents (
                        source_type, source_uri, external_ref, title, content, content_sha256, indexed_generation, status, metadata
                    ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, CAST(? AS jsonb))
                    """;
        }
        return """
                INSERT INTO rag_documents (
                    source_type, source_uri, external_ref, title, content, content_sha256, indexed_generation, status, metadata
                ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)
                """;
    }

    private String updateSql(Connection connection) throws java.sql.SQLException {
        if (isPostgresSql(connection)) {
            return """
                    UPDATE rag_documents
                       SET source_type = ?,
                           source_uri = ?,
                           external_ref = ?,
                           title = ?,
                           content = ?,
                           content_sha256 = ?,
                           status = ?,
                           attempt_count = 0,
                           metadata = CAST(? AS jsonb),
                           last_error = NULL,
                           last_attempted_at = NULL,
                           updated_at = now()
                     WHERE id = ? AND status <> 'DELETING'
                    """;
        }
        return """
                UPDATE rag_documents
                   SET source_type = ?,
                       source_uri = ?,
                       external_ref = ?,
                       title = ?,
                       content = ?,
                       content_sha256 = ?,
                       status = ?,
                       attempt_count = 0,
                       metadata = ?,
                       last_error = NULL,
                       last_attempted_at = NULL,
                       updated_at = now()
                 WHERE id = ? AND status <> 'DELETING'
                """;
    }

    private void bindMetadata(PreparedStatement statement, Connection connection, int index, Map<String, Object> metadataJson)
            throws java.sql.SQLException {
        // PostgreSQL 走 jsonb，测试环境/H2 退化为普通字符串列。
        if (metadataJson == null) {
            if (isPostgresSql(connection)) {
                statement.setNull(index, Types.OTHER);
            } else {
                statement.setNull(index, Types.VARCHAR);
            }
            return;
        }
        statement.setString(index, jsonCodec.write(metadataJson));
    }

    private RowMapper<RagDocumentRecord> rowMapper() {
        return (rs, _) -> new RagDocumentRecord(
                rs.getLong("id"),
                rs.getString("source_type"),
                rs.getString("source_uri"),
                rs.getString("external_ref"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("content_sha256"),
                (Long) rs.getObject("indexed_generation"),
                rs.getString("status"),
                rs.getInt("chunk_count"),
                rs.getInt("attempt_count"),
                jsonCodec.readMap(rs.getString("metadata")),
                rs.getString("last_error"),
                toOffsetDateTime(rs.getTimestamp("last_attempted_at")),
                toOffsetDateTime(rs.getTimestamp("indexed_at")),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at"))
        );
    }

    private boolean isPostgresSql(Connection connection) throws java.sql.SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
    }

    private void updateRequired(String sql, Long id, Object... args) {
        int updatedRows = jdbc.update(sql, args);
        if (updatedRows == 0) {
            throw new IllegalArgumentException("RAG 文档不存在: " + id);
        }
    }

    private void updateUnlessDeleting(String sql, Long id, Object... args) {
        int updatedRows = jdbc.update(sql, args);
        if (updatedRows > 0) {
            return;
        }
        Optional<RagDocumentRecord> current = findById(id);
        if (current.isPresent() && RagDocumentStatus.DELETING.name().equals(current.get().status())) {
            return;
        }
        throw new IllegalArgumentException("RAG 文档不存在: " + id);
    }

    private Number extractId(KeyHolder keyHolder) {
        if (keyHolder.getKeys() != null) {
            Object id = keyHolder.getKeys().get("id");
            if (id instanceof Number number) {
                return number;
            }
        }
        return keyHolder.getKey();
    }
}
