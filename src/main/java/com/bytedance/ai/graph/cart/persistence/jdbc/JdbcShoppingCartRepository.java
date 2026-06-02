package com.bytedance.ai.graph.cart.persistence.jdbc;

import com.bytedance.ai.graph.cart.persistence.ShoppingCartRecord;
import com.bytedance.ai.graph.cart.persistence.ShoppingCartRepository;
import com.bytedance.ai.graph.cart.api.CartState;
import com.bytedance.ai.shared.support.RagJsonCodec;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 购物车仓储的 JDBC 实现。
 */
@Repository
public class JdbcShoppingCartRepository implements ShoppingCartRepository {

    private final JdbcTemplate jdbc;
    private final RagJsonCodec jsonCodec;

    public JdbcShoppingCartRepository(JdbcTemplate jdbc, RagJsonCodec jsonCodec) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShoppingCartRecord create(String userId, String conversationId) {
        // PostgreSQL-friendly: INSERT ... RETURNING * returns the freshly persisted row in one
        // round-trip. Avoids the KeyHolder dance (PG returns every inserted column as a generated
        // key, which trips up Spring's single-key shortcut) and the follow-up SELECT.
        String cartId = "cart_" + UUID.randomUUID().toString().replace("-", "");
        ShoppingCartRecord created = jdbc.queryForObject(
                """
                INSERT INTO shopping_cart (cart_id, user_id, conversation_id)
                VALUES (?, ?, ?)
                RETURNING *
                """,
                rowMapper(),
                cartId,
                userId,
                conversationId
        );
        if (created == null) {
            throw new IllegalStateException("创建购物车失败，未返回数据");
        }
        return created;
    }

    @Override
    public Optional<ShoppingCartRecord> findLatestActive(String userId, String conversationId) {
        return jdbc.query(
                """
                SELECT * FROM shopping_cart
                 WHERE user_id = ?
                   AND conversation_id = ?
                   AND state NOT IN ('PLACED', 'CANCELLED')
                 ORDER BY updated_at DESC, id DESC
                 LIMIT 1
                """,
                rowMapper(),
                userId,
                conversationId
        ).stream().findFirst();
    }

    @Override
    public Optional<ShoppingCartRecord> findLatestActiveWithItemsByUser(String userId) {
        return jdbc.query(
                """
                SELECT c.*
                  FROM shopping_cart c
                 WHERE c.user_id = ?
                   AND c.state NOT IN ('PLACED', 'CANCELLED')
                   AND EXISTS (
                       SELECT 1
                         FROM cart_item i
                        WHERE i.cart_id = c.id
                          AND i.status = 'ACTIVE'
                   )
                 ORDER BY c.updated_at DESC, c.id DESC
                 LIMIT 1
                """,
                rowMapper(),
                userId
        ).stream().findFirst();
    }

    @Override
    public Optional<ShoppingCartRecord> findById(Long id) {
        return jdbc.query("SELECT * FROM shopping_cart WHERE id = ?", rowMapper(), id).stream().findFirst();
    }

    @Override
    public void updateState(Long id, CartState state) {
        jdbc.update(
                "UPDATE shopping_cart SET state = ?, version = version + 1, updated_at = now() WHERE id = ?",
                state.name(),
                id
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTotals(Long id, BigDecimal subtotalAmount, int itemCount) {
        jdbc.update(
                """
                UPDATE shopping_cart
                   SET subtotal_amount = ?,
                       item_count = ?,
                       version = version + 1,
                       updated_at = now()
                 WHERE id = ?
                """,
                subtotalAmount == null ? BigDecimal.ZERO : subtotalAmount,
                itemCount,
                id
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateShippingAddress(Long id, Map<String, Object> shippingAddress) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(updateAddressSql(connection));
            statement.setString(1, jsonCodec.write(shippingAddress == null ? Map.of() : shippingAddress));
            statement.setLong(2, id);
            return statement;
        });
    }

    private String updateAddressSql(Connection connection) throws SQLException {
        if (isPostgreSql(connection)) {
            return """
                    UPDATE shopping_cart
                       SET shipping_address_json = CAST(? AS jsonb),
                           version = version + 1,
                           updated_at = now()
                     WHERE id = ?
                    """;
        }
        return """
                UPDATE shopping_cart
                   SET shipping_address_json = ?,
                       version = version + 1,
                       updated_at = now()
                 WHERE id = ?
                """;
    }

    private boolean isPostgreSql(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
    }

    private RowMapper<ShoppingCartRecord> rowMapper() {
        return (rs, _) -> new ShoppingCartRecord(
                rs.getLong("id"),
                rs.getString("cart_id"),
                rs.getString("user_id"),
                rs.getString("conversation_id"),
                CartState.valueOf(rs.getString("state")),
                rs.getString("currency"),
                rs.getBigDecimal("subtotal_amount"),
                rs.getInt("item_count"),
                jsonCodec.readMap(rs.getString("shipping_address_json")),
                rs.getLong("version"),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at"))
        );
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
