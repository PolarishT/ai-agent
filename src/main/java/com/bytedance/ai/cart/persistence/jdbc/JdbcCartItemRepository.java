package com.bytedance.ai.cart.persistence.jdbc;

import com.bytedance.ai.cart.persistence.CartItemRecord;
import com.bytedance.ai.cart.persistence.CartItemRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class JdbcCartItemRepository implements CartItemRepository {

    private final JdbcTemplate jdbc;

    public JdbcCartItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CartItemRecord upsertActive(
            Long cartId,
            Long spuId,
            String externalRef,
            String title,
            String brand,
            String imageUrl,
            int quantity,
            BigDecimal unitPrice,
            Integer stockSnapshot
    ) {
        Optional<CartItemRecord> existing = findActive(cartId, null, spuId, externalRef);
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
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    """
                    INSERT INTO cart_item (
                        cart_id, spu_id, external_ref, title, brand, image_url,
                        quantity, unit_price, stock_snapshot
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    java.sql.Statement.RETURN_GENERATED_KEYS
            );
            statement.setLong(1, cartId);
            statement.setLong(2, spuId);
            statement.setString(3, externalRef);
            statement.setString(4, title);
            statement.setString(5, brand);
            statement.setString(6, imageUrl);
            statement.setInt(7, quantity);
            statement.setBigDecimal(8, unitPrice);
            if (stockSnapshot == null) {
                statement.setNull(9, java.sql.Types.INTEGER);
            } else {
                statement.setInt(9, stockSnapshot);
            }
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null && keyHolder.getKeys() != null && keyHolder.getKeys().get("id") instanceof Number number) {
            id = number;
        }
        if (id == null) {
            throw new IllegalStateException("创建购物车明细失败，未返回主键");
        }
        return findActive(cartId, id.longValue(), null, null).orElseThrow();
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
