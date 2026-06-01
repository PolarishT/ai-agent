package com.bytedance.ai.graph.cartmanage.persistence.jdbc;

import com.bytedance.ai.graph.cartmanage.subgraph.CartAction;
import com.bytedance.ai.graph.cartmanage.subgraph.CartWorkflowStatus;
import com.bytedance.ai.graph.cartmanage.persistence.PendingCartActionRecord;
import com.bytedance.ai.graph.cartmanage.persistence.PendingCartActionRepository;
import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcPendingCartActionRepository implements PendingCartActionRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcPendingCartActionRepository.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcPendingCartActionRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public PendingCartActionRecord save(PendingCartActionRecord record) {
        if (record.id() == null) {
            return insert(record);
        }
        return update(record);
    }

    private PendingCartActionRecord insert(PendingCartActionRecord record) {
        String sql = """
                INSERT INTO public.pending_cart_actions
                (user_id, conversation_id, action, product_name, quantity, candidates, status, created_at, updated_at, expire_at)
                VALUES (:userId, :conversationId, :action, :productName, :quantity, :candidates::jsonb, :status, :createdAt, :updatedAt, :expireAt)
                RETURNING id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", record.userId())
                .addValue("conversationId", record.conversationId())
                .addValue("action", record.action().name())
                .addValue("productName", record.productName())
                .addValue("quantity", record.quantity())
                .addValue("candidates", toJson(record.candidates()))
                .addValue("status", record.status().name())
                .addValue("createdAt", record.createdAt())
                .addValue("updatedAt", record.updatedAt())
                .addValue("expireAt", record.expireAt());
        Long id = jdbcTemplate.queryForObject(sql, params, Long.class);
        return new PendingCartActionRecord(id, record.userId(), record.conversationId(),
                record.action(), record.productName(), record.quantity(), record.candidates(),
                record.status(), record.createdAt(), record.updatedAt(), record.expireAt());
    }

    private PendingCartActionRecord update(PendingCartActionRecord record) {
        String sql = """
                UPDATE public.pending_cart_actions
                SET status = :status, updated_at = :updatedAt
                WHERE id = :id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", record.id())
                .addValue("status", record.status().name())
                .addValue("updatedAt", LocalDateTime.now());
        jdbcTemplate.update(sql, params);
        return record;
    }

    @Override
    public Optional<PendingCartActionRecord> findActiveByUserIdAndConversationId(String userId, String conversationId) {
        String sql = """
                SELECT * FROM public.pending_cart_actions
                WHERE user_id = :userId
                  AND conversation_id = :conversationId
                  AND status = 'WAITING_USER_SELECTION'
                  AND expire_at > NOW()
                ORDER BY created_at DESC
                LIMIT 1
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("conversationId", conversationId);
        List<PendingCartActionRecord> results = jdbcTemplate.query(sql, params, new PendingCartActionRowMapper(objectMapper));
        return results.stream().findFirst();
    }

    @Override
    public void markCompleted(Long id) {
        String sql = "UPDATE public.pending_cart_actions SET status = 'ADD_SUCCESS', updated_at = NOW() WHERE id = :id";
        jdbcTemplate.update(sql, new MapSqlParameterSource("id", id));
    }

    @Override
    public void markCancelled(Long id) {
        String sql = "UPDATE public.pending_cart_actions SET status = 'CANCELLED', updated_at = NOW() WHERE id = :id";
        jdbcTemplate.update(sql, new MapSqlParameterSource("id", id));
    }

    @Override
    public void deleteExpired() {
        String sql = "DELETE FROM public.pending_cart_actions WHERE expire_at < NOW()";
        int deleted = jdbcTemplate.update(sql, new MapSqlParameterSource());
        log.info("Deleted {} expired pending cart actions", deleted);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to serialize to JSON", e);
            return "[]";
        }
    }

    private static class PendingCartActionRowMapper implements RowMapper<PendingCartActionRecord> {
        private final ObjectMapper mapper;
        private final TypeReference<List<ProductCandidate>> candidateType = new TypeReference<>() {};

        PendingCartActionRowMapper(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public PendingCartActionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PendingCartActionRecord(
                    rs.getLong("id"),
                    rs.getString("user_id"),
                    rs.getString("conversation_id"),
                    CartAction.valueOf(rs.getString("action")),
                    rs.getString("product_name"),
                    (Integer) rs.getObject("quantity"),
                    fromJson(rs.getString("candidates")),
                    CartWorkflowStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("updated_at").toLocalDateTime(),
                    rs.getTimestamp("expire_at").toLocalDateTime()
            );
        }

        private List<ProductCandidate> fromJson(String json) {
            try {
                return mapper.readValue(json, candidateType);
            } catch (Exception e) {
                log.warn("Failed to deserialize candidates", e);
                return List.of();
            }
        }
    }
}
