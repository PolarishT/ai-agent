package com.bytedance.ai.graph.catalog.persistence.jdbc;

import com.bytedance.ai.graph.catalog.persistence.CatalogSkuRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogSkuRepository;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Spring JDBC 的 catalog_sku 仓储实现。
 */
@Repository
public class JdbcCatalogSkuRepository implements CatalogSkuRepository {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final JdbcTemplate jdbc;
    private final RagJsonCodec jsonCodec;

    public JdbcCatalogSkuRepository(JdbcTemplate jdbc, RagJsonCodec jsonCodec) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public List<CatalogSkuRecord> saveAll(Long productId, List<SkuDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        List<CatalogSkuRecord> saved = new ArrayList<>(drafts.size());
        for (SkuDraft draft : drafts) {
            saved.add(insertOne(productId, draft));
        }
        return saved;
    }

    private CatalogSkuRecord insertOne(Long productId, SkuDraft draft) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(insertSql(connection), Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, productId);
            statement.setInt(2, draft.skuIndex());
            statement.setString(3, jsonCodec.write(draft.propertiesJson() == null ? java.util.Map.of() : draft.propertiesJson()));
            statement.setBigDecimal(4, draft.price());
            statement.setInt(5, draft.stock());
            statement.setString(6, STATUS_ACTIVE);
            statement.setString(7, jsonCodec.write(draft.rawJson() == null ? java.util.Map.of() : draft.rawJson()));
            return statement;
        }, keyHolder);

        Number id = extractId(keyHolder);
        if (id == null) {
            throw new IllegalStateException("创建 catalog_sku 失败，未返回主键");
        }
        return findById(id.longValue());
    }

    @Override
    public List<CatalogSkuRecord> findByProductId(Long productId) {
        return jdbc.query(
                "SELECT * FROM catalog_sku WHERE product_id = ? ORDER BY sku_index, id",
                rowMapper(),
                productId
        );
    }

    @Override
    public boolean decreaseStock(Long productId, Long skuId, int quantity) {
        int updated = jdbc.update(
                """
                UPDATE catalog_sku
                   SET stock = stock - ?, updated_at = now()
                 WHERE id = ?
                   AND product_id = ?
                   AND status = 'ACTIVE'
                   AND stock >= ?
                """,
                quantity,
                skuId,
                productId,
                quantity
        );
        return updated > 0;
    }

    private CatalogSkuRecord findById(long id) {
        List<CatalogSkuRecord> results = jdbc.query(
                "SELECT * FROM catalog_sku WHERE id = ?",
                rowMapper(),
                id
        );
        return results.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("创建 catalog_sku 后查询失败: " + id));
    }

    private String insertSql(Connection connection) throws SQLException {
        if (isPostgreSql(connection)) {
            return """
                    INSERT INTO catalog_sku (
                        product_id, sku_index, properties_json, price, stock, status, raw_json
                    ) VALUES (?, ?, CAST(? AS jsonb), ?, ?, ?, CAST(? AS jsonb))
                    """;
        }
        return """
                INSERT INTO catalog_sku (
                    product_id, sku_index, properties_json, price, stock, status, raw_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
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

    private RowMapper<CatalogSkuRecord> rowMapper() {
        return (rs, rowNum) -> new CatalogSkuRecord(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getInt("sku_index"),
                jsonCodec.readMap(rs.getString("properties_json")),
                rs.getBigDecimal("price"),
                rs.getInt("stock"),
                rs.getString("status"),
                jsonCodec.readMap(rs.getString("raw_json")),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at"))
        );
    }
}
