package com.bytedance.ai.order.persistence.jdbc;

import com.bytedance.ai.order.persistence.CustomerOrderRecord;
import com.bytedance.ai.order.persistence.CustomerOrderRepository;
import com.bytedance.ai.shared.support.RagJsonCodec;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCustomerOrderRepository implements CustomerOrderRepository {

    private final JdbcTemplate jdbc;
    private final RagJsonCodec jsonCodec;

    public JdbcCustomerOrderRepository(JdbcTemplate jdbc, RagJsonCodec jsonCodec) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public CustomerOrderRecord save(
            String cartId,
            String userId,
            String conversationId,
            String currency,
            BigDecimal subtotalAmount,
            int itemCount,
            Long deliveryAddressId,
            Map<String, Object> deliveryAddress,
            List<Map<String, Object>> priceChanges
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(insertSql(connection), java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "ord_" + UUID.randomUUID().toString().replace("-", ""));
            statement.setString(2, cartId);
            statement.setString(3, userId);
            statement.setString(4, conversationId);
            statement.setString(5, currency);
            statement.setBigDecimal(6, subtotalAmount == null ? BigDecimal.ZERO : subtotalAmount);
            statement.setInt(7, itemCount);
            if (deliveryAddressId == null) {
                statement.setNull(8, java.sql.Types.BIGINT);
            } else {
                statement.setLong(8, deliveryAddressId);
            }
            statement.setString(9, jsonCodec.write(deliveryAddress == null ? Map.of() : deliveryAddress));
            statement.setString(10, jsonCodec.write(priceChanges == null ? List.of() : priceChanges));
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null && keyHolder.getKeys() != null && keyHolder.getKeys().get("id") instanceof Number number) {
            id = number;
        }
        if (id == null) {
            throw new IllegalStateException("创建订单失败，未返回主键");
        }
        Long generatedId = id.longValue();
        return jdbc.query("SELECT * FROM customer_order WHERE id = ?", rowMapper(), generatedId)
                .stream()
                .findFirst()
                .orElseThrow();
    }

    @Override
    public Optional<CustomerOrderRecord> findByOrderId(String orderId) {
        return jdbc.query("SELECT * FROM customer_order WHERE order_id = ?", rowMapper(), orderId)
                .stream()
                .findFirst();
    }

    private String insertSql(Connection connection) throws SQLException {
        if (isPostgreSql(connection)) {
            return """
                    INSERT INTO customer_order (
                        order_id, cart_id, user_id, conversation_id, currency,
                        subtotal_amount, item_count, delivery_address_id,
                        delivery_address_json, price_change_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
                    """;
        }
        return """
                INSERT INTO customer_order (
                    order_id, cart_id, user_id, conversation_id, currency,
                    subtotal_amount, item_count, delivery_address_id,
                    delivery_address_json, price_change_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
    }

    private boolean isPostgreSql(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
    }

    private RowMapper<CustomerOrderRecord> rowMapper() {
        return (rs, _) -> new CustomerOrderRecord(
                rs.getLong("id"),
                rs.getString("order_id"),
                rs.getString("cart_id"),
                rs.getString("user_id"),
                rs.getString("conversation_id"),
                rs.getString("status"),
                rs.getString("currency"),
                rs.getBigDecimal("subtotal_amount"),
                rs.getInt("item_count"),
                (Long) rs.getObject("delivery_address_id"),
                jsonCodec.readMap(rs.getString("delivery_address_json")),
                readListOfMaps(rs.getString("price_change_json")),
                toOffsetDateTime(rs.getTimestamp("placed_at")),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at"))
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readListOfMaps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        Object parsed = jsonCodec.read(json, List.class);
        if (!(parsed instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
