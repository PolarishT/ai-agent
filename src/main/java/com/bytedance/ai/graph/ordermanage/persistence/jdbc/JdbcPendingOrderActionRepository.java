package com.bytedance.ai.graph.ordermanage.persistence.jdbc;

import com.bytedance.ai.graph.ordermanage.OrderManageStatus;
import com.bytedance.ai.graph.ordermanage.persistence.PendingOrderActionRecord;
import com.bytedance.ai.graph.ordermanage.persistence.PendingOrderActionRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcPendingOrderActionRepository implements PendingOrderActionRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcPendingOrderActionRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<PendingOrderActionRecord> findActiveByUserIdAndConversationId(String userId, String conversationId) {
        String sql = """
                SELECT * FROM public.pending_order_actions
                 WHERE user_id = :userId
                   AND conversation_id = :conversationId
                   AND status IN ('WAITING_ADDRESS', 'WAITING_CONFIRMATION', 'CREATING')
                 ORDER BY created_at DESC, id DESC
                 LIMIT 1
                """;
        List<PendingOrderActionRecord> records = jdbcTemplate.query(sql, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("conversationId", conversationId), rowMapper());
        return records.stream().findFirst();
    }

    @Override
    public Optional<PendingOrderActionRecord> findById(Long id) {
        return jdbcTemplate.query(
                "SELECT * FROM public.pending_order_actions WHERE id = :id",
                new MapSqlParameterSource("id", id),
                rowMapper()
        ).stream().findFirst();
    }

    @Override
    public PendingOrderActionRecord save(PendingOrderActionRecord record) {
        String sql = """
                INSERT INTO public.pending_order_actions
                (user_id, conversation_id, cart_snapshot, cart_snapshot_hash, address_snapshot,
                 amount_snapshot, status, fail_reason, order_no, created_at, updated_at, expire_at)
                VALUES (:userId, :conversationId, :cartSnapshot::jsonb, :cartSnapshotHash, :addressSnapshot::jsonb,
                        :amountSnapshot, :status, :failReason, :orderNo, :createdAt, :updatedAt, :expireAt)
                RETURNING id
                """;
        Long id = jdbcTemplate.queryForObject(sql, params(record), Long.class);
        return new PendingOrderActionRecord(
                id,
                record.userId(),
                record.conversationId(),
                record.cartSnapshot(),
                record.cartSnapshotHash(),
                record.addressSnapshot(),
                record.amountSnapshot(),
                record.status(),
                record.failReason(),
                record.orderNo(),
                record.createdAt(),
                record.updatedAt(),
                record.expireAt()
        );
    }

    @Override
    public void updateAddress(Long id, Map<String, Object> addressSnapshot) {
        jdbcTemplate.update("""
                UPDATE public.pending_order_actions
                   SET address_snapshot = :addressSnapshot::jsonb,
                       updated_at = NOW()
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("addressSnapshot", toJson(addressSnapshot)));
    }

    @Override
    public void markWaitingAddress(Long id) {
        markStatus(id, OrderManageStatus.WAITING_ADDRESS, null, null);
    }

    @Override
    public void markWaitingConfirmation(
            Long id,
            Map<String, Object> cartSnapshot,
            String cartSnapshotHash,
            Map<String, Object> addressSnapshot,
            BigDecimal amount
    ) {
        jdbcTemplate.update("""
                UPDATE public.pending_order_actions
                   SET status = 'WAITING_CONFIRMATION',
                       cart_snapshot = :cartSnapshot::jsonb,
                       cart_snapshot_hash = :cartSnapshotHash,
                       address_snapshot = :addressSnapshot::jsonb,
                       amount_snapshot = :amount,
                       updated_at = NOW()
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("cartSnapshot", toJson(cartSnapshot))
                .addValue("cartSnapshotHash", cartSnapshotHash)
                .addValue("addressSnapshot", toJson(addressSnapshot))
                .addValue("amount", amount));
    }

    @Override
    public boolean markCreatingIfWaitingConfirmation(Long id) {
        int updated = jdbcTemplate.update("""
                UPDATE public.pending_order_actions
                   SET status = 'CREATING', updated_at = NOW()
                 WHERE id = :id
                   AND status = 'WAITING_CONFIRMATION'
                """, new MapSqlParameterSource("id", id));
        return updated == 1;
    }

    @Override
    public void markCreated(Long id, String orderNo) {
        markStatus(id, OrderManageStatus.ORDER_CREATED, null, orderNo);
    }

    @Override
    public void markCancelled(Long id) {
        markStatus(id, OrderManageStatus.CANCELLED, null, null);
    }

    @Override
    public void markFailed(Long id, String reason) {
        markStatus(id, OrderManageStatus.FAILED, reason, null);
    }

    @Override
    public void markExpired(Long id) {
        markStatus(id, OrderManageStatus.EXPIRED, "pending order expired", null);
    }

    private void markStatus(Long id, OrderManageStatus status, String reason, String orderNo) {
        jdbcTemplate.update("""
                UPDATE public.pending_order_actions
                   SET status = :status,
                       fail_reason = COALESCE(:reason, fail_reason),
                       order_no = COALESCE(:orderNo, order_no),
                       updated_at = NOW()
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status.name())
                .addValue("reason", reason)
                .addValue("orderNo", orderNo));
    }

    private MapSqlParameterSource params(PendingOrderActionRecord record) {
        return new MapSqlParameterSource()
                .addValue("userId", record.userId())
                .addValue("conversationId", record.conversationId())
                .addValue("cartSnapshot", toJson(record.cartSnapshot()))
                .addValue("cartSnapshotHash", record.cartSnapshotHash())
                .addValue("addressSnapshot", toJson(record.addressSnapshot()))
                .addValue("amountSnapshot", record.amountSnapshot())
                .addValue("status", record.status().name())
                .addValue("failReason", record.failReason())
                .addValue("orderNo", record.orderNo())
                .addValue("createdAt", record.createdAt())
                .addValue("updatedAt", record.updatedAt())
                .addValue("expireAt", record.expireAt());
    }

    private RowMapper<PendingOrderActionRecord> rowMapper() {
        return new RowMapper<>() {
            private final TypeReference<Map<String, Object>> mapType = new TypeReference<>() {
            };

            @Override
            public PendingOrderActionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
                return new PendingOrderActionRecord(
                        rs.getLong("id"),
                        rs.getString("user_id"),
                        rs.getString("conversation_id"),
                        fromJson(rs.getString("cart_snapshot")),
                        rs.getString("cart_snapshot_hash"),
                        fromJson(rs.getString("address_snapshot")),
                        rs.getBigDecimal("amount_snapshot"),
                        OrderManageStatus.valueOf(rs.getString("status")),
                        rs.getString("fail_reason"),
                        rs.getString("order_no"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime(),
                        rs.getTimestamp("expire_at").toLocalDateTime()
                );
            }

            private Map<String, Object> fromJson(String json) {
                try {
                    return objectMapper.readValue(json == null || json.isBlank() ? "{}" : json, mapType);
                } catch (Exception exception) {
                    return Map.of();
                }
            }
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            return "{}";
        }
    }
}
