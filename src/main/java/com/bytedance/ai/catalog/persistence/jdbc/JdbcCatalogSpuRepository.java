package com.bytedance.ai.catalog.persistence.jdbc;

import com.bytedance.ai.catalog.persistence.CatalogSpuRecord;
import com.bytedance.ai.catalog.persistence.CatalogSpuRepository;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 Spring JDBC 的 catalog_spu 仓储实现。
 *
 * <p>风格与 {@code JdbcRagDocumentRepository} 对齐：PostgreSQL 走 jsonb 强类型，
 * H2 / 其它测试库退化为普通字符串列。
 */
@Repository
public class JdbcCatalogSpuRepository implements CatalogSpuRepository {

    private static final String ATTR_STATUS_PENDING = "PENDING";
    private static final String ATTR_STATUS_RUNNING = "RUNNING";
    private static final String ATTR_STATUS_DONE = "DONE";
    private static final String ATTR_STATUS_FAILED = "FAILED";

    private final JdbcTemplate jdbc;
    private final RagJsonCodec jsonCodec;

    public JdbcCatalogSpuRepository(JdbcTemplate jdbc, RagJsonCodec jsonCodec) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public CatalogSpuRecord save(
            String externalRef,
            String title,
            String brand,
            String categoryPath,
            BigDecimal priceMin,
            BigDecimal priceMax,
            int stock,
            String descriptionMd,
            List<String> images,
            String videoUrl
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(insertSql(connection), Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, externalRef);
            statement.setString(2, title);
            statement.setString(3, brand);
            statement.setString(4, categoryPath);
            setNullableBigDecimal(statement, 5, priceMin);
            setNullableBigDecimal(statement, 6, priceMax);
            statement.setInt(7, stock);
            statement.setString(8, descriptionMd);
            bindJson(statement, connection, 9, images == null ? List.of() : images);
            statement.setString(10, videoUrl);
            return statement;
        }, keyHolder);

        Number id = extractId(keyHolder);
        if (id == null) {
            throw new IllegalStateException("创建 catalog_spu 失败，未返回主键");
        }
        return findById(id.longValue()).orElseThrow(() -> new IllegalStateException("创建 catalog_spu 后查询失败"));
    }

    @Override
    public void attachDocument(Long spuId, Long documentId) {
        int updated = jdbc.update(
                """
                UPDATE catalog_spu
                   SET document_id = ?, updated_at = now()
                 WHERE id = ?
                """,
                documentId,
                spuId
        );
        if (updated == 0) {
            throw new IllegalArgumentException("catalog_spu 不存在: " + spuId);
        }
    }

    @Override
    public Optional<CatalogSpuRecord> findById(Long id) {
        List<CatalogSpuRecord> results = jdbc.query(
                "SELECT * FROM catalog_spu WHERE id = ?",
                rowMapper(),
                id
        );
        return results.stream().findFirst();
    }

    @Override
    public Optional<CatalogSpuRecord> findByExternalRef(String externalRef) {
        List<CatalogSpuRecord> results = jdbc.query(
                "SELECT * FROM catalog_spu WHERE external_ref = ?",
                rowMapper(),
                externalRef
        );
        return results.stream().findFirst();
    }

    @Override
    public boolean decreaseStock(Long spuId, int quantity) {
        int updated = jdbc.update(
                """
                UPDATE catalog_spu
                   SET stock = stock - ?,
                       version = version + 1,
                       updated_at = now()
                 WHERE id = ?
                   AND status = 'ACTIVE'
                   AND stock >= ?
                """,
                quantity,
                spuId,
                quantity
        );
        return updated > 0;
    }

    @Override
    public boolean markAttributeExtractionRunning(Long spuId) {
        int updated = jdbc.update(
                """
                UPDATE catalog_spu
                   SET attributes_status = ?,
                       attributes_attempt_count = attributes_attempt_count + 1,
                       attributes_attempted_at = now(),
                       attributes_last_error = NULL,
                       updated_at = now()
                 WHERE id = ?
                   AND attributes_status IN (?, ?)
                """,
                ATTR_STATUS_RUNNING,
                spuId,
                ATTR_STATUS_PENDING,
                ATTR_STATUS_FAILED
        );
        return updated > 0;
    }

    @Override
    public void markAttributeExtractionSucceeded(Long spuId, Map<String, Object> attributesJson) {
        Map<String, Object> safeAttrs = attributesJson == null ? Map.of() : attributesJson;
        int updated = jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(succeededSql(connection));
            statement.setString(1, ATTR_STATUS_DONE);
            bindJson(statement, connection, 2, safeAttrs);
            statement.setLong(3, spuId);
            return statement;
        });
        if (updated == 0) {
            throw new EmptyResultDataAccessException("catalog_spu 不存在或并发覆盖: " + spuId, 1);
        }
    }

    @Override
    public void markAttributeExtractionFailed(Long spuId, String errorMessage) {
        int updated = jdbc.update(
                """
                UPDATE catalog_spu
                   SET attributes_status = ?,
                       attributes_last_error = ?,
                       updated_at = now()
                 WHERE id = ?
                """,
                ATTR_STATUS_FAILED,
                errorMessage,
                spuId
        );
        if (updated == 0) {
            throw new EmptyResultDataAccessException("catalog_spu 不存在: " + spuId, 1);
        }
    }

    @Override
    public List<CatalogSpuRecord> findByAttributesStatus(String status, int limit) {
        return jdbc.query(
                """
                SELECT * FROM catalog_spu
                 WHERE attributes_status = ?
                 ORDER BY updated_at
                 LIMIT ?
                """,
                rowMapper(),
                status,
                limit
        );
    }

    // ---------- SQL fragments ----------

    private String insertSql(Connection connection) throws SQLException {
        if (isPostgreSql(connection)) {
            return """
                    INSERT INTO catalog_spu (
                        external_ref, title, brand, category_path,
                        price_min, price_max, stock, description_md,
                        images, video_url
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                    """;
        }
        return """
                INSERT INTO catalog_spu (
                    external_ref, title, brand, category_path,
                    price_min, price_max, stock, description_md,
                    images, video_url
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
    }

    private String succeededSql(Connection connection) throws SQLException {
        if (isPostgreSql(connection)) {
            return """
                    UPDATE catalog_spu
                       SET attributes_status = ?,
                           attributes_json = CAST(? AS jsonb),
                           attributes_last_error = NULL,
                           updated_at = now()
                     WHERE id = ?
                    """;
        }
        return """
                UPDATE catalog_spu
                   SET attributes_status = ?,
                       attributes_json = ?,
                       attributes_last_error = NULL,
                       updated_at = now()
                 WHERE id = ?
                """;
    }

    // ---------- Helpers ----------

    private void setNullableBigDecimal(PreparedStatement statement, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NUMERIC);
        } else {
            statement.setBigDecimal(index, value);
        }
    }

    private void bindJson(PreparedStatement statement, Connection connection, int index, Object payload) throws SQLException {
        statement.setString(index, jsonCodec.write(payload));
    }

    private boolean isPostgreSql(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
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

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }

    private RowMapper<CatalogSpuRecord> rowMapper() {
        return (rs, rowNum) -> new CatalogSpuRecord(
                rs.getLong("id"),
                rs.getString("external_ref"),
                rs.getString("title"),
                rs.getString("brand"),
                rs.getString("category_path"),
                rs.getBigDecimal("price_min"),
                rs.getBigDecimal("price_max"),
                rs.getInt("stock"),
                rs.getString("description_md"),
                readStringList(rs.getString("images")),
                rs.getString("video_url"),
                jsonCodec.readMap(rs.getString("attributes_json")),
                rs.getString("attributes_status"),
                rs.getInt("attributes_attempt_count"),
                rs.getString("attributes_last_error"),
                toOffsetDateTime(rs.getTimestamp("attributes_attempted_at")),
                rs.getString("status"),
                rs.getLong("version"),
                (Long) rs.getObject("document_id"),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at"))
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        Object parsed = jsonCodec.read(json, List.class);
        if (parsed == null) {
            return List.of();
        }
        return ((List<?>) parsed).stream().map(String::valueOf).toList();
    }
}
