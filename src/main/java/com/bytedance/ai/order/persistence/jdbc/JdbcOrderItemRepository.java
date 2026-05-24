package com.bytedance.ai.order.persistence.jdbc;

import com.bytedance.ai.order.persistence.OrderItemRecord;
import com.bytedance.ai.order.persistence.OrderItemRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrderItemRepository implements OrderItemRepository {

    private final JdbcTemplate jdbc;

    public JdbcOrderItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(
            Long orderId,
            Long spuId,
            String externalRef,
            String title,
            String brand,
            String imageUrl,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineAmount
    ) {
        jdbc.update(
                """
                INSERT INTO order_item (
                    order_id, spu_id, external_ref, title, brand, image_url,
                    quantity, unit_price, line_amount
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                orderId,
                spuId,
                externalRef,
                title,
                brand,
                imageUrl,
                quantity,
                unitPrice,
                lineAmount
        );
    }

    @Override
    public List<OrderItemRecord> findByOrderId(Long orderId) {
        return jdbc.query("SELECT * FROM order_item WHERE order_id = ? ORDER BY id", rowMapper(), orderId);
    }

    private RowMapper<OrderItemRecord> rowMapper() {
        return (rs, _) -> new OrderItemRecord(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getLong("spu_id"),
                rs.getString("external_ref"),
                rs.getString("title"),
                rs.getString("brand"),
                rs.getString("image_url"),
                rs.getInt("quantity"),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("line_amount"),
                toOffsetDateTime(rs.getTimestamp("created_at"))
        );
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
