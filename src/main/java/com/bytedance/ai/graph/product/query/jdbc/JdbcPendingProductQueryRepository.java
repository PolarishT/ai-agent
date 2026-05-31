package com.bytedance.ai.graph.product.query.jdbc;

import com.bytedance.ai.graph.product.query.PendingProductQueryAction;
import com.bytedance.ai.graph.product.query.PendingProductQueryRepository;
import com.bytedance.ai.graph.product.query.PendingProductQueryStatus;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductSearchCandidate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcPendingProductQueryRepository implements PendingProductQueryRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcPendingProductQueryRepository.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcPendingProductQueryRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public PendingProductQueryAction save(PendingProductQueryAction record) {
        // 同会话只保留一条 ACTIVE：先把旧 ACTIVE 行翻 SUPERSEDED，再插入新行。
        String supersedeSql = """
                UPDATE public.pending_product_query_actions
                   SET status = 'SUPERSEDED', updated_at = :now
                 WHERE user_id = :userId
                   AND conversation_id = :conversationId
                   AND status = 'ACTIVE'
                """;
        jdbcTemplate.update(supersedeSql, new MapSqlParameterSource()
                .addValue("userId", record.userId())
                .addValue("conversationId", record.conversationId())
                .addValue("now", LocalDateTime.now()));

        String insertSql = """
                INSERT INTO public.pending_product_query_actions
                (user_id, conversation_id, condition_json, candidates_json,
                 turn_count, status, created_at, updated_at, expire_at)
                VALUES (:userId, :conversationId, :conditionJson::jsonb, :candidatesJson::jsonb,
                        :turnCount, :status, :createdAt, :updatedAt, :expireAt)
                RETURNING id
                """;
        Long id = jdbcTemplate.queryForObject(insertSql, new MapSqlParameterSource()
                .addValue("userId", record.userId())
                .addValue("conversationId", record.conversationId())
                .addValue("conditionJson", toJson(record.condition()))
                .addValue("candidatesJson", toJson(record.candidates()))
                .addValue("turnCount", record.turnCount())
                .addValue("status", record.status().name())
                .addValue("createdAt", record.createdAt())
                .addValue("updatedAt", record.updatedAt())
                .addValue("expireAt", record.expireAt()), Long.class);
        return new PendingProductQueryAction(
                id,
                record.userId(),
                record.conversationId(),
                record.condition(),
                record.candidates(),
                record.turnCount(),
                record.status(),
                record.createdAt(),
                record.updatedAt(),
                record.expireAt()
        );
    }

    @Override
    public Optional<PendingProductQueryAction> findActiveByUserIdAndConversationId(String userId, String conversationId) {
        String sql = """
                SELECT * FROM public.pending_product_query_actions
                 WHERE user_id = :userId
                   AND conversation_id = :conversationId
                   AND status = 'ACTIVE'
                   AND expire_at > NOW()
                 ORDER BY created_at DESC
                 LIMIT 1
                """;
        List<PendingProductQueryAction> results = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("conversationId", conversationId),
                rowMapper()
        );
        return results.stream().findFirst();
    }

    @Override
    public void markSuperseded(Long id) {
        jdbcTemplate.update(
                "UPDATE public.pending_product_query_actions SET status = 'SUPERSEDED', updated_at = NOW() WHERE id = :id",
                new MapSqlParameterSource("id", id)
        );
    }

    @Override
    public void markExpired(Long id) {
        jdbcTemplate.update(
                "UPDATE public.pending_product_query_actions SET status = 'EXPIRED', updated_at = NOW() WHERE id = :id",
                new MapSqlParameterSource("id", id)
        );
    }

    @Override
    public int deleteExpired() {
        int deleted = jdbcTemplate.update(
                "DELETE FROM public.pending_product_query_actions WHERE expire_at < NOW()",
                new MapSqlParameterSource()
        );
        if (deleted > 0) {
            log.info("Deleted {} expired pending product query rows", deleted);
        }
        return deleted;
    }

    private RowMapper<PendingProductQueryAction> rowMapper() {
        return (rs, rowNum) -> new PendingProductQueryAction(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("conversation_id"),
                fromConditionJson(rs.getString("condition_json")),
                fromCandidatesJson(rs.getString("candidates_json")),
                rs.getInt("turn_count"),
                PendingProductQueryStatus.valueOf(rs.getString("status")),
                toLocalDateTime(rs, "created_at"),
                toLocalDateTime(rs, "updated_at"),
                toLocalDateTime(rs, "expire_at")
        );
    }

    private static LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toLocalDateTime();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            log.warn("Failed to serialize pending_product_query payload to JSON", exception);
            return "{}";
        }
    }

    private ProductQueryCondition fromConditionJson(String json) {
        if (json == null || json.isBlank()) {
            return ProductQueryCondition.empty("");
        }
        try {
            return objectMapper.readValue(json, ProductQueryCondition.class);
        } catch (RuntimeException exception) {
            log.warn("Failed to deserialize pending_product_query condition; returning empty", exception);
            return ProductQueryCondition.empty("");
        }
    }

    private List<ProductSearchCandidate> fromCandidatesJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (RuntimeException exception) {
            log.warn("Failed to deserialize pending_product_query candidates; returning empty", exception);
            return List.of();
        }
    }
}
