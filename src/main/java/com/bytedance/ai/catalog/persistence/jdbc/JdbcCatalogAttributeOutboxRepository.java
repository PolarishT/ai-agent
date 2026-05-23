package com.bytedance.ai.catalog.persistence.jdbc;

import com.bytedance.ai.catalog.persistence.CatalogAttributeOutboxRecord;
import com.bytedance.ai.catalog.persistence.CatalogAttributeOutboxRepository;
import com.bytedance.ai.catalog.persistence.CatalogAttributeOutboxStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Spring JDBC 实现的 catalog 抽属性 Outbox 仓储。
 *
 * <p>PostgreSQL 走 jsonb，H2 测试库退化为 CLOB / VARCHAR；JDBC 层通过
 * {@link Connection#getMetaData()} 自动检测，复用 catalog_spu / rag_chunks 的同款套路。
 */
@Repository
public class JdbcCatalogAttributeOutboxRepository implements CatalogAttributeOutboxRepository {

    private static final RowMapper<CatalogAttributeOutboxRecord> ROW_MAPPER = (rs, rowNum) ->
            new CatalogAttributeOutboxRecord(
                    rs.getLong("id"),
                    rs.getLong("spu_id"),
                    rs.getString("external_ref"),
                    rs.getString("payload_json"),
                    rs.getString("status"),
                    rs.getInt("attempt_count"),
                    rs.getString("last_error"),
                    toOffsetDateTime(rs.getTimestamp("next_send_after")),
                    rs.getString("message_id"),
                    toOffsetDateTime(rs.getTimestamp("created_at")),
                    toOffsetDateTime(rs.getTimestamp("updated_at"))
            );

    private final JdbcTemplate jdbc;

    public JdbcCatalogAttributeOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enqueue(Long spuId, String externalRef, String payloadJson) {
        // 同一 SPU 仍有 PENDING / FAILED / SENDING 的 outbox 行时复用，避免无限堆积重复消息。
        int updatedRows = jdbc.update(
                """
                UPDATE catalog_attribute_outbox
                   SET status = ?, attempt_count = 0, last_error = NULL, message_id = NULL,
                       next_send_after = now(), updated_at = now()
                 WHERE spu_id = ? AND status IN (?, ?, ?)
                """,
                CatalogAttributeOutboxStatus.PENDING.name(),
                spuId,
                CatalogAttributeOutboxStatus.PENDING.name(),
                CatalogAttributeOutboxStatus.FAILED.name(),
                CatalogAttributeOutboxStatus.SENDING.name()
        );
        if (updatedRows > 0) {
            // 仅在 payload 实质变化时覆盖，否则保留原始 payload 即可。
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(updatePayloadSql(connection));
                bindPayload(statement, connection, 1, payloadJson);
                statement.setLong(2, spuId);
                return statement;
            });
            return;
        }

        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(insertSql(connection));
                statement.setLong(1, spuId);
                statement.setString(2, externalRef);
                bindPayload(statement, connection, 3, payloadJson);
                statement.setString(4, CatalogAttributeOutboxStatus.PENDING.name());
                return statement;
            });
        } catch (DuplicateKeyException ignored) {
            // 并发 enqueue 时最终仍收敛到唯一一行；递归一次走 UPDATE 分支。
            enqueue(spuId, externalRef, payloadJson);
        }
    }

    @Override
    public List<CatalogAttributeOutboxRecord> findDispatchable(OffsetDateTime now, int limit) {
        return jdbc.query(
                """
                SELECT * FROM catalog_attribute_outbox
                 WHERE status IN (?, ?)
                   AND (next_send_after IS NULL OR next_send_after <= ?)
                 ORDER BY created_at ASC
                 LIMIT ?
                """,
                ROW_MAPPER,
                CatalogAttributeOutboxStatus.PENDING.name(),
                CatalogAttributeOutboxStatus.FAILED.name(),
                Timestamp.from(now.toInstant()),
                limit
        );
    }

    @Override
    public boolean markSending(Long id) {
        int updatedRows = jdbc.update(
                """
                UPDATE catalog_attribute_outbox
                   SET status = ?, updated_at = now()
                 WHERE id = ? AND status IN (?, ?)
                """,
                CatalogAttributeOutboxStatus.SENDING.name(),
                id,
                CatalogAttributeOutboxStatus.PENDING.name(),
                CatalogAttributeOutboxStatus.FAILED.name()
        );
        return updatedRows > 0;
    }

    @Override
    public void markSent(Long id, String messageId) {
        jdbc.update(
                """
                UPDATE catalog_attribute_outbox
                   SET status = ?, message_id = ?, last_error = NULL, updated_at = now()
                 WHERE id = ?
                """,
                CatalogAttributeOutboxStatus.SENT.name(),
                messageId,
                id
        );
    }

    @Override
    public void markFailed(Long id, String errorMessage, OffsetDateTime nextSendAfter) {
        jdbc.update(
                """
                UPDATE catalog_attribute_outbox
                   SET status = ?, attempt_count = attempt_count + 1,
                       last_error = ?, next_send_after = ?, updated_at = now()
                 WHERE id = ?
                """,
                CatalogAttributeOutboxStatus.FAILED.name(),
                errorMessage,
                nextSendAfter == null ? null : Timestamp.from(nextSendAfter.toInstant()),
                id
        );
    }

    @Override
    public List<CatalogAttributeOutboxRecord> findStuckSendingBefore(OffsetDateTime cutoff, int limit) {
        return jdbc.query(
                """
                SELECT * FROM catalog_attribute_outbox
                 WHERE status = ?
                   AND updated_at <= ?
                 ORDER BY updated_at ASC
                 LIMIT ?
                """,
                ROW_MAPPER,
                CatalogAttributeOutboxStatus.SENDING.name(),
                Timestamp.from(cutoff.toInstant()),
                limit
        );
    }

    @Override
    public void resetForRetry(Long id, String errorMessage, OffsetDateTime nextSendAfter) {
        jdbc.update(
                """
                UPDATE catalog_attribute_outbox
                   SET status = ?, last_error = ?, next_send_after = ?, updated_at = now()
                 WHERE id = ? AND status = ?
                """,
                CatalogAttributeOutboxStatus.FAILED.name(),
                errorMessage,
                nextSendAfter == null ? null : Timestamp.from(nextSendAfter.toInstant()),
                id,
                CatalogAttributeOutboxStatus.SENDING.name()
        );
    }

    @Override
    public Optional<CatalogAttributeOutboxRecord> findLatestBySpuId(Long spuId) {
        List<CatalogAttributeOutboxRecord> records = jdbc.query(
                """
                SELECT * FROM catalog_attribute_outbox
                 WHERE spu_id = ?
                 ORDER BY id DESC
                 LIMIT 1
                """,
                ROW_MAPPER,
                spuId
        );
        return records.stream().findFirst();
    }

    // ---- SQL fragments ----

    private String insertSql(Connection connection) throws SQLException {
        if (isPostgreSql(connection)) {
            return """
                    INSERT INTO catalog_attribute_outbox (
                        spu_id, external_ref, payload_json, status, next_send_after
                    ) VALUES (?, ?, CAST(? AS jsonb), ?, now())
                    """;
        }
        return """
                INSERT INTO catalog_attribute_outbox (
                    spu_id, external_ref, payload_json, status, next_send_after
                ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
    }

    private String updatePayloadSql(Connection connection) throws SQLException {
        if (isPostgreSql(connection)) {
            return """
                    UPDATE catalog_attribute_outbox
                       SET payload_json = CAST(? AS jsonb), updated_at = now()
                     WHERE spu_id = ? AND status = 'PENDING'
                    """;
        }
        return """
                UPDATE catalog_attribute_outbox
                   SET payload_json = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE spu_id = ? AND status = 'PENDING'
                """;
    }

    private void bindPayload(PreparedStatement statement, Connection connection, int index, String payloadJson) throws SQLException {
        statement.setString(index, payloadJson == null ? "{}" : payloadJson);
    }

    private boolean isPostgreSql(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
