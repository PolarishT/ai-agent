package com.bytedance.ai.order.persistence.jdbc;

import com.bytedance.ai.order.persistence.DeliveryAddressRecord;
import com.bytedance.ai.order.persistence.DeliveryAddressRepository;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDeliveryAddressRepository implements DeliveryAddressRepository {

    private final JdbcTemplate jdbc;

    public JdbcDeliveryAddressRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DeliveryAddressRecord save(String userId, Map<String, Object> address, boolean isDefault) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    """
                    INSERT INTO delivery_address (
                        user_id, receiver_name, phone, province, city, district,
                        detail, postal_code, is_default
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    java.sql.Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, userId);
            statement.setString(2, text(address, "receiverName", "默认收货人"));
            statement.setString(3, text(address, "phone", "13800000000"));
            statement.setString(4, text(address, "province", "默认省份"));
            statement.setString(5, text(address, "city", "默认城市"));
            statement.setString(6, text(address, "district", "默认区域"));
            statement.setString(7, text(address, "detail", "默认地址，请在客户端补充真实收货地址"));
            statement.setString(8, text(address, "postalCode", ""));
            statement.setBoolean(9, isDefault);
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null && keyHolder.getKeys() != null && keyHolder.getKeys().get("id") instanceof Number number) {
            id = number;
        }
        if (id == null) {
            throw new IllegalStateException("创建收货地址失败，未返回主键");
        }
        Long generatedId = id.longValue();
        return jdbc.query("SELECT * FROM delivery_address WHERE id = ?", rowMapper(), generatedId)
                .stream()
                .findFirst()
                .orElseThrow();
    }

    @Override
    public DeliveryAddressRecord saveDefaultIfAbsent(String userId) {
        Optional<DeliveryAddressRecord> existing = findDefaultByUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        return save(userId, Map.of(), true);
    }

    @Override
    public Optional<DeliveryAddressRecord> findDefaultByUserId(String userId) {
        return jdbc.query(
                """
                SELECT * FROM delivery_address
                 WHERE user_id = ?
                   AND is_default = TRUE
                 ORDER BY updated_at DESC, id DESC
                 LIMIT 1
                """,
                rowMapper(),
                userId
        ).stream().findFirst();
    }

    private RowMapper<DeliveryAddressRecord> rowMapper() {
        return (rs, _) -> new DeliveryAddressRecord(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("receiver_name"),
                rs.getString("phone"),
                rs.getString("province"),
                rs.getString("city"),
                rs.getString("district"),
                rs.getString("detail"),
                rs.getString("postal_code"),
                rs.getBoolean("is_default"),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at"))
        );
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }

    private String text(Map<String, Object> address, String key, String defaultValue) {
        Object value = address == null ? null : address.get(key);
        if (value == null || !org.springframework.util.StringUtils.hasText(String.valueOf(value))) {
            return defaultValue;
        }
        return String.valueOf(value).trim();
    }
}
