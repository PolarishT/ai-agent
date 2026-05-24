package com.bytedance.ai.cart.persistence.jdbc;

import com.bytedance.ai.cart.persistence.CartTransitionAuditRepository;
import com.bytedance.ai.shared.support.RagJsonCodec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCartTransitionAuditRepository implements CartTransitionAuditRepository {

    private final JdbcTemplate jdbc;
    private final RagJsonCodec jsonCodec;

    public JdbcCartTransitionAuditRepository(JdbcTemplate jdbc, RagJsonCodec jsonCodec) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public void save(
            Long cartId,
            String businessCartId,
            String fromState,
            String toState,
            String event,
            String triggeredBy,
            boolean success,
            String failureReason,
            String errorMessage,
            Map<String, Object> metadata
    ) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(insertSql(connection));
            if (cartId == null) {
                statement.setNull(1, java.sql.Types.BIGINT);
            } else {
                statement.setLong(1, cartId);
            }
            statement.setString(2, businessCartId);
            statement.setString(3, fromState);
            statement.setString(4, toState);
            statement.setString(5, event);
            statement.setString(6, triggeredBy);
            statement.setBoolean(7, success);
            statement.setString(8, failureReason);
            statement.setString(9, errorMessage);
            statement.setString(10, jsonCodec.write(metadata == null ? Map.of() : metadata));
            return statement;
        });
    }

    private String insertSql(Connection connection) throws SQLException {
        if (isPostgreSql(connection)) {
            return """
                    INSERT INTO cart_transition_audit (
                        cart_id, business_cart_id, from_state, to_state, event,
                        triggered_by, success, failure_reason, error_message, metadata
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """;
        }
        return """
                INSERT INTO cart_transition_audit (
                    cart_id, business_cart_id, from_state, to_state, event,
                    triggered_by, success, failure_reason, error_message, metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
    }

    private boolean isPostgreSql(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
    }
}
