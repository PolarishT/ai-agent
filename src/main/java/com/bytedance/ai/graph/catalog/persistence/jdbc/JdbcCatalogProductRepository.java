package com.bytedance.ai.graph.catalog.persistence.jdbc;

import com.bytedance.ai.graph.catalog.persistence.CatalogProductRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductRepository;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 商品目录仓储的 JDBC 实现。
 */
@Repository
public class JdbcCatalogProductRepository implements CatalogProductRepository {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String ATTR_STATUS_RUNNING = "RUNNING";
    private static final String ATTR_STATUS_DONE = "DONE";
    private static final String ATTR_STATUS_FAILED = "FAILED";

    private final JdbcTemplate jdbc;
    private final RagJsonCodec jsonCodec;

    public JdbcCatalogProductRepository(JdbcTemplate jdbc, RagJsonCodec jsonCodec) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public CatalogProductRecord save(
            String title,
            String brand,
            String category,
            String subCategory,
            BigDecimal basePrice,
            BigDecimal priceMin,
            BigDecimal priceMax,
            int totalStock,
            String imagePath,
            Map<String, Object> attributesJson,
            Map<String, Object> rawJson
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(insertSql(connection), Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, title);
            statement.setString(2, brand);
            statement.setString(3, category);
            statement.setString(4, subCategory);
            statement.setBigDecimal(5, basePrice == null ? BigDecimal.ZERO : basePrice);
            statement.setBigDecimal(6, priceMin);
            statement.setBigDecimal(7, priceMax);
            statement.setInt(8, totalStock);
            statement.setString(9, imagePath);
            statement.setString(10, STATUS_ACTIVE);
            statement.setString(11, jsonCodec.write(attributesJson == null ? Map.of() : attributesJson));
            statement.setString(12, jsonCodec.write(rawJson == null ? Map.of() : rawJson));
            return statement;
        }, keyHolder);
        Number id = extractId(keyHolder);
        if (id == null) {
            throw new IllegalStateException("创建 catalog_product 失败，未返回主键");
        }
        return findById(id.longValue()).orElseThrow(() -> new IllegalStateException("创建 catalog_product 后查询失败"));
    }

    @Override
    public Optional<CatalogProductRecord> findById(Long id) {
        List<CatalogProductRecord> results = jdbc.query(
                "SELECT * FROM catalog_product WHERE id = ?",
                rowMapper(),
                id
        );
        return results.stream().findFirst();
    }

    @Override
    public List<CatalogProductRecord> searchActiveByKeyword(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        int safeLimit = limit <= 0 ? 5 : Math.min(limit, 20);
        String normalized = keyword.trim();
        String like = "%" + normalized.toLowerCase() + "%";
        return jdbc.query(
                """
                SELECT *,
                       CASE
                         WHEN title = ? THEN 100
                         WHEN lower(title) = lower(?) THEN 95
                         WHEN lower(title) LIKE ? THEN 80
                         WHEN lower(coalesce(brand, '')) LIKE ? THEN 50
                         WHEN lower(coalesce(category, '')) LIKE ? THEN 40
                         WHEN lower(coalesce(sub_category, '')) LIKE ? THEN 35
                         ELSE 0
                       END AS match_score
                  FROM catalog_product
                 WHERE status = 'ACTIVE'
                   AND (
                        lower(title) LIKE ?
                     OR lower(coalesce(brand, '')) LIKE ?
                     OR lower(coalesce(category, '')) LIKE ?
                     OR lower(coalesce(sub_category, '')) LIKE ?
                   )
                 ORDER BY match_score DESC, updated_at DESC, id DESC
                 LIMIT ?
                """,
                rowMapper(),
                normalized,
                normalized,
                like,
                like,
                like,
                like,
                like,
                like,
                like,
                like,
                safeLimit
        );
    }

    @Override
    public boolean decreaseStock(Long productId, int quantity) {
        int updated = jdbc.update(
                """
                UPDATE catalog_product
                   SET total_stock = total_stock - ?, updated_at = now()
                 WHERE id = ?
                   AND status = 'ACTIVE'
                   AND total_stock >= ?
                """,
                quantity,
                productId,
                quantity
        );
        return updated > 0;
    }

    @Override
    public boolean markAttributeExtractionRunning(Long productId) {
        return Boolean.TRUE.equals(jdbc.execute((Connection connection) -> {
            if (isPostgreSql(connection)) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE catalog_product
                           SET raw_json = jsonb_set(coalesce(raw_json, '{}'::jsonb), '{attributesStatus}', to_jsonb(?::text), true),
                               updated_at = now()
                         WHERE id = ?
                        """)) {
                    statement.setString(1, ATTR_STATUS_RUNNING);
                    statement.setLong(2, productId);
                    return statement.executeUpdate() > 0;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE catalog_product SET updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                statement.setLong(1, productId);
                return statement.executeUpdate() > 0;
            }
        }));
    }

    @Override
    public void markAttributeExtractionSucceeded(Long productId, Map<String, Object> attributesJson) {
        int updated = jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(updateAttributesSql(connection));
            statement.setString(1, jsonCodec.write(attributesJson == null ? Map.of() : attributesJson));
            statement.setLong(2, productId);
            return statement;
        });
        if (updated == 0) {
            throw new EmptyResultDataAccessException("catalog_product 不存在: " + productId, 1);
        }
    }

    @Override
    public void markAttributeExtractionFailed(Long productId, String errorMessage) {
        int updated = jdbc.update(
                "UPDATE catalog_product SET updated_at = now() WHERE id = ?",
                productId
        );
        if (updated == 0) {
            throw new EmptyResultDataAccessException("catalog_product 不存在: " + productId, 1);
        }
    }

    private String insertSql(Connection connection) throws SQLException {
        if (isPostgreSql(connection)) {
            return """
                    INSERT INTO catalog_product (
                        title, brand, category, sub_category, base_price, price_min, price_max,
                        total_stock, image_path, status, attributes_json, raw_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
                    """;
        }
        return """
                INSERT INTO catalog_product (
                    title, brand, category, sub_category, base_price, price_min, price_max,
                    total_stock, image_path, status, attributes_json, raw_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
    }

    private String updateAttributesSql(Connection connection) throws SQLException {
        if (isPostgreSql(connection)) {
            return "UPDATE catalog_product SET attributes_json = CAST(? AS jsonb), updated_at = now() WHERE id = ?";
        }
        return "UPDATE catalog_product SET attributes_json = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
    }

    private RowMapper<CatalogProductRecord> rowMapper() {
        return (rs, rowNum) -> new CatalogProductRecord(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("brand"),
                rs.getString("category"),
                rs.getString("sub_category"),
                rs.getBigDecimal("base_price"),
                rs.getBigDecimal("price_min"),
                rs.getBigDecimal("price_max"),
                rs.getInt("total_stock"),
                rs.getString("image_path"),
                rs.getString("status"),
                jsonCodec.readMap(rs.getString("attributes_json")),
                jsonCodec.readMap(rs.getString("raw_json")),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at"))
        );
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
}
