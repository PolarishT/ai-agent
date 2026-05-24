package com.bytedance.ai.cart.persistence.jdbc;

import com.bytedance.ai.cart.persistence.ShoppingCartRecord;
import com.bytedance.ai.cart.persistence.ShoppingCartRepository;
import com.bytedance.ai.cart.api.CartState;
import com.bytedance.ai.shared.support.RagJsonCodec;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcShoppingCartRepository implements ShoppingCartRepository {

    private final JdbcTemplate jdbc;
    private final RagJsonCodec jsonCodec;

    public JdbcShoppingCartRepository(JdbcTemplate jdbc, RagJsonCodec jsonCodec) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public ShoppingCartRecord create(String userId, String conversationId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(insertSql(connection), Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "cart_" + UUID.randomUUID().toString().replace("-", ""));
            statement.setString(2, userId);
            statement.setString(3, conversationId);
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null && keyHolder.getKeys() != null && keyHolder.getKeys().get("id") instanceof Number number) {
            id = number;
        }
        if (id == null) {
            throw new IllegalStateException("创建购物车失败，未返回主键");
        }
        return findById(id.longValue()).orElseThrow(() -> new IllegalStateException("创建购物车后查询失败"));
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
    public void updateShippingAddress(Long id, Map<String, Object> shippingAddress) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(updateAddressSql(connection));
            statement.setString(1, jsonCodec.write(shippingAddress == null ? Map.of() : shippingAddress));
            statement.setLong(2, id);
            return statement;
        });
    }

    private String insertSql(Connection connection) throws SQLException {
        if (isPostgreSql(connection)) {
            return """
                    INSERT INTO shopping_cart (cart_id, user_id, conversation_id)
                    VALUES (?, ?, ?)
                    """;
        }
        return "INSERT INTO shopping_cart (cart_id, user_id, conversation_id) VALUES (?, ?, ?)";
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
