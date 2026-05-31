package com.bytedance.ai.graph.catalog.persistence.jdbc;

import com.bytedance.ai.graph.catalog.persistence.CatalogAttributeOutboxRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogAttributeOutboxRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogAttributeOutboxStatus;
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
 */
@Repository
public class JdbcCatalogAttributeOutboxRepository implements CatalogAttributeOutboxRepository {

    private static final RowMapper<CatalogAttributeOutboxRecord> ROW_MAPPER = (rs, rowNum) ->
            new CatalogAttributeOutboxRecord(
                    rs.getLong("id"),
                    rs.getLong("product_id"),
                    rs.getString("event_type"),
                    rs.getString("metadata"),
                    rs.getString("status"),
                    rs.getInt("attempt_count"),
                    rs.getString("last_error"),
                    toOffsetDateTime(rs.getTimestamp("next_attempt_at")),
                    rs.getString("message_id"),
                    toOffsetDateTime(rs.getTimestamp("created_at")),
                    toOffsetDateTime(rs.getTimestamp("updated_at"))
            );

    private final JdbcTemplate jdbc;

    public JdbcCatalogAttributeOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enqueue(Long productId, String eventType, String metadataJson) {
        String safeEventType = eventType == null || eventType.isBlank() ? "EXTRACT_ATTRIBUTES" : eventType;
        // 同一 Product 同事件仍有 NEW / FAILED / SENDING 的 outbox 行时复用，避免无限堆积重复消息。
        int updatedRows = jdbc.update(
                """
                UPDATE catalog_attribute_outbox
                   SET status = ?, attempt_count = 0, last_error = NULL, message_id = NULL,
                       next_attempt_at = now(), updated_at = now()
                 WHERE product_id = ? AND event_type = ? AND status IN (?, ?, ?)
                """,
                CatalogAttributeOutboxStatus.NEW.name(),
                productId,
                safeEventType,
                CatalogAttributeOutboxStatus.NEW.name(),
                CatalogAttributeOutboxStatus.FAILED.name(),
                CatalogAttributeOutboxStatus.SENDING.name()
        );
        if (updatedRows > 0) {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(updatePayloadSql(connection));
                bindPayload(statement, connection, 1, metadataJson);
                statement.setLong(2, productId);
                statement.setString(3, safeEventType);
                return statement;
            });
            return;
        }

        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(insertSql(connection));
                statement.setLong(1, productId);
                statement.setString(2, safeEventType);
                statement.setString(3, CatalogAttributeOutboxStatus.NEW.name());
                bindPayload(statement, connection, 4, metadataJson);
                return statement;
            });
        } catch (DuplicateKeyException ignored) {
            // 并发 enqueue 时最终仍收敛到唯一一行；递归一次走 UPDATE 分支。
            enqueue(productId, safeEventType, metadataJson);
        }
    }

    @Override
    public List<CatalogAttributeOutboxRecord> findDispatchable(OffsetDateTime now, int limit) {
        return jdbc.query(
                """
                SELECT * FROM catalog_attribute_outbox
                 WHERE status IN (?, ?)
                   AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                 ORDER BY created_at ASC
                 LIMIT ?
                """,
                ROW_MAPPER,
                CatalogAttributeOutboxStatus.NEW.name(),
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
                CatalogAttributeOutboxStatus.NEW.name(),
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
                       last_error = ?, next_attempt_at = ?, updated_at = now()
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
                   SET status = ?, last_error = ?, next_attempt_at = ?, updated_at = now()
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
    public Optional<CatalogAttributeOutboxRecord> findLatestByProductId(Long productId) {
        List<CatalogAttributeOutboxRecord> records = jdbc.query(
                """
                SELECT * FROM catalog_attribute_outbox
                 WHERE product_id = ?
                 ORDER BY id DESC
                 LIMIT 1
                """,
                ROW_MAPPER,
                productId
        );
        return records.stream().findFirst();
    }

    // ---- SQL fragments ----

    private String insertSql(Connection connection) throws SQLException {
        if (isPostgreSql(connection)) {
            return """
                    INSERT INTO catalog_attribute_outbox (
                        product_id, event_type, status, metadata, next_attempt_at
                    ) VALUES (?, ?, ?, CAST(? AS jsonb), now())
                    """;
        }
        return """
                INSERT INTO catalog_attribute_outbox (
                    product_id, event_type, status, metadata, next_attempt_at
                ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
    }

    private String updatePayloadSql(Connection connection) throws SQLException {
        if (isPostgreSql(connection)) {
            return """
                    UPDATE catalog_attribute_outbox
                       SET metadata = CAST(? AS jsonb), updated_at = now()
                     WHERE product_id = ? AND event_type = ? AND status = 'NEW'
                    """;
        }
        return """
                UPDATE catalog_attribute_outbox
                   SET metadata = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE product_id = ? AND event_type = ? AND status = 'NEW'
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
