package com.bytedance.ai.graph.cart.persistence.jdbc;

import com.bytedance.ai.graph.cart.persistence.CartItemRecord;
import com.bytedance.ai.graph.cart.persistence.CartItemRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 购物车仓储的 JDBC 实现。
 */
@Repository
public class JdbcCartItemRepository implements CartItemRepository {

    private final JdbcTemplate jdbc;
    /** 数据库类型在运行期不变，首次探测后缓存：PG 走原子 ON CONFLICT，其它库走兼容路径。 */
    private volatile Boolean postgres;

    public JdbcCartItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CartItemRecord upsertActive(
            Long cartId,
            Long spuId,
            Long skuId,
            String externalRef,
            String title,
            String brand,
            String imageUrl,
            int quantity,
            BigDecimal unitPrice,
            Integer stockSnapshot
    ) {
        if (isPostgres()) {
            return upsertActivePostgres(
                    cartId, spuId, skuId, externalRef, title, brand, imageUrl, quantity, unitPrice, stockSnapshot);
        }
        // 非 PostgreSQL（如测试用 H2）退化为「先查再写」：这些环境为单线程集成测试，无并发竞争。
        Optional<CartItemRecord> existing = findActiveForUpsert(cartId, spuId, skuId);
        if (existing.isPresent()) {
            CartItemRecord item = existing.get();
            jdbc.update(
                    """
                    UPDATE cart_item
                       SET quantity = quantity + ?,
                           unit_price = ?,
                           stock_snapshot = ?,
                           updated_at = now()
                     WHERE id = ?
                    """,
                    quantity,
                    unitPrice,
                    stockSnapshot,
                    item.id()
            );
            return findActive(cartId, item.id(), null, null).orElseThrow();
        }
        // 兼容路径首次插入：INSERT ... RETURNING * 一次往返完成落库并读回（H2 PostgreSQL 模式同样支持）。
        CartItemRecord created = jdbc.queryForObject(
                """
                INSERT INTO cart_item (
                    cart_id, spu_id, sku_id, external_ref, title, brand, image_url,
                    quantity, unit_price, stock_snapshot
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING *
                """,
                rowMapper(),
                cartId,
                spuId,
                skuId,
                externalRef,
                title,
                brand,
                imageUrl,
                quantity,
                unitPrice,
                stockSnapshot
        );
        if (created == null) {
            throw new IllegalStateException("创建购物车明细失败，未返回数据");
        }
        return created;
    }

    private CartItemRecord upsertActivePostgres(
            Long cartId,
            Long spuId,
            Long skuId,
            String externalRef,
            String title,
            String brand,
            String imageUrl,
            int quantity,
            BigDecimal unitPrice,
            Integer stockSnapshot
    ) {
        // 单条 INSERT ... ON CONFLICT DO UPDATE：命中部分唯一索引 uq_cart_item_active_line 时，
        // 数据库原子地把数量累加到既有 ACTIVE 明细，彻底消除「先查再插」的并发重复行。
        CartItemRecord merged = jdbc.queryForObject(
                """
                INSERT INTO cart_item (
                    cart_id, spu_id, sku_id, external_ref, title, brand, image_url,
                    quantity, unit_price, stock_snapshot
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (cart_id, spu_id, sku_id) WHERE status = 'ACTIVE'
                DO UPDATE SET
                    quantity = cart_item.quantity + EXCLUDED.quantity,
                    unit_price = EXCLUDED.unit_price,
                    stock_snapshot = EXCLUDED.stock_snapshot,
                    updated_at = now()
                RETURNING *
                """,
                rowMapper(),
                cartId,
                spuId,
                skuId,
                externalRef,
                title,
                brand,
                imageUrl,
                quantity,
                unitPrice,
                stockSnapshot
        );
        if (merged == null) {
            throw new IllegalStateException("购物车明细 upsert 未返回数据");
        }
        return merged;
    }

    private boolean isPostgres() {
        Boolean cached = postgres;
        if (cached == null) {
            cached = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) JdbcCartItemRepository::isPostgreSql));
            postgres = cached;
        }
        return cached;
    }

    private static boolean isPostgreSql(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
    }

    private Optional<CartItemRecord> findActiveForUpsert(Long cartId, Long spuId, Long skuId) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM cart_item WHERE cart_id = ? AND status = 'ACTIVE' AND spu_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(cartId);
        args.add(spuId);
        if (skuId == null) {
            sql.append(" AND sku_id IS NULL");
        } else {
            sql.append(" AND sku_id = ?");
            args.add(skuId);
        }
        sql.append(" ORDER BY updated_at DESC, id DESC LIMIT 1");
        return jdbc.query(sql.toString(), rowMapper(), args.toArray()).stream().findFirst();
    }

    @Override
    public Optional<CartItemRecord> findActive(Long cartId, Long itemId, Long spuId, String externalRef) {
        StringBuilder sql = new StringBuilder("SELECT * FROM cart_item WHERE cart_id = ? AND status = 'ACTIVE'");
        List<Object> args = new ArrayList<>();
        args.add(cartId);
        if (itemId != null) {
            sql.append(" AND id = ?");
            args.add(itemId);
        }
        if (spuId != null) {
            sql.append(" AND spu_id = ?");
            args.add(spuId);
        }
        if (StringUtils.hasText(externalRef)) {
            sql.append(" AND external_ref = ?");
            args.add(externalRef);
        }
        sql.append(" ORDER BY updated_at DESC, id DESC LIMIT 1");
        return jdbc.query(sql.toString(), rowMapper(), args.toArray()).stream().findFirst();
    }

    @Override
    public List<CartItemRecord> findActiveByCartId(Long cartId) {
        return jdbc.query(
                "SELECT * FROM cart_item WHERE cart_id = ? AND status = 'ACTIVE' ORDER BY id",
                rowMapper(),
                cartId
        );
    }

    @Override
    public void updateQuantity(Long itemId, int quantity) {
        jdbc.update("UPDATE cart_item SET quantity = ?, updated_at = now() WHERE id = ?", quantity, itemId);
    }

    @Override
    public void markRemoved(Long itemId) {
        jdbc.update("UPDATE cart_item SET status = 'REMOVED', updated_at = now() WHERE id = ?", itemId);
    }

    private RowMapper<CartItemRecord> rowMapper() {
        return (rs, _) -> new CartItemRecord(
                rs.getLong("id"),
                rs.getLong("cart_id"),
                rs.getLong("spu_id"),
                (Long) rs.getObject("sku_id"),
                rs.getString("external_ref"),
                rs.getString("title"),
                rs.getString("brand"),
                rs.getString("image_url"),
                rs.getInt("quantity"),
                rs.getBigDecimal("unit_price"),
                (Integer) rs.getObject("stock_snapshot"),
                rs.getString("status"),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at"))
        );
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
