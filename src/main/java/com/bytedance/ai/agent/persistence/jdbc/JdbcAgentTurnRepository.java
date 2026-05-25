package com.bytedance.ai.agent.persistence.jdbc;

import com.bytedance.ai.agent.persistence.AgentTurnRecord;
import com.bytedance.ai.agent.persistence.AgentTurnRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcAgentTurnRepository implements AgentTurnRepository {

    private static final RowMapper<AgentTurnRecord> ROW_MAPPER = (rs, _) -> new AgentTurnRecord(
            rs.getLong("id"),
            rs.getString("turn_id"),
            rs.getString("correlation_id"),
            rs.getString("user_id"),
            rs.getString("conversation_id"),
            rs.getString("request_id"),
            rs.getString("user_message_id"),
            rs.getString("assistant_message_id"),
            rs.getString("status"),
            rs.getString("user_message"),
            rs.getString("intent"),
            rs.getString("intent_source"),
            toDouble(rs.getBigDecimal("intent_confidence")),
            rs.getString("slots_json"),
            rs.getString("tools_called"),
            rs.getString("cards_emitted"),
            (Boolean) rs.getObject("generated_by_model"),
            rs.getString("answer_text"),
            rs.getString("memory_summary"),
            (Integer) rs.getObject("memory_summary_message_count"),
            rs.getString("memory_summary_model"),
            (Integer) rs.getObject("tokens_in"),
            (Integer) rs.getObject("tokens_out"),
            (Integer) rs.getObject("latency_ms"),
            rs.getString("error_code"),
            rs.getString("error_message"),
            toOffsetDateTime(rs.getTimestamp("started_at")),
            toOffsetDateTime(rs.getTimestamp("completed_at"))
    );

    private final JdbcTemplate jdbc;

    public JdbcAgentTurnRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String defaultObjectJson(String json) {
        return json == null || json.isBlank() ? "{}" : json;
    }

    private static String defaultArrayJson(String json) {
        return json == null || json.isBlank() ? "[]" : json;
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }

    @Override
    public void createRunning(
            String turnId,
            String correlationId,
            String userId,
            String conversationId,
            String requestId,
            String userMessage
    ) {
        jdbc.update(
                """
                        INSERT INTO agent_turn (
                            turn_id, correlation_id, user_id, conversation_id, request_id, user_message, status
                        ) VALUES (?, ?, ?, ?, ?, ?, 'RUNNING')
                        """,
                turnId,
                correlationId,
                userId,
                conversationId,
                requestId,
                userMessage
        );
    }

    @Override
    public Optional<AgentTurnRecord> findByTurnId(String turnId) {
        return jdbc.query(
                "SELECT * FROM agent_turn WHERE turn_id = ?",
                ROW_MAPPER,
                turnId
        ).stream().findFirst();
    }

    @Override
    public Optional<AgentTurnRecord> findByRequestId(String userId, String conversationId, String requestId) {
        return jdbc.query(
                """
                        SELECT *
                          FROM agent_turn
                         WHERE user_id = ?
                           AND conversation_id = ?
                           AND request_id = ?
                        """,
                ROW_MAPPER,
                userId,
                conversationId,
                requestId
        ).stream().findFirst();
    }

    @Override
    public List<AgentTurnRecord> findRecentByConversationId(String conversationId, int limit) {
        return jdbc.query(
                """
                        SELECT *
                          FROM agent_turn
                         WHERE conversation_id = ?
                         ORDER BY started_at DESC, id DESC
                         LIMIT ?
                        """,
                ROW_MAPPER,
                conversationId,
                limit
        );
    }

    @Override
    public Optional<AgentTurnRecord> findLatestMemorySummary(String conversationId) {
        return jdbc.query(
                """
                        SELECT *
                          FROM agent_turn
                         WHERE conversation_id = ?
                           AND status = 'SUCCEEDED'
                           AND memory_summary IS NOT NULL
                           AND memory_summary <> ''
                         ORDER BY completed_at DESC NULLS LAST, started_at DESC, id DESC
                         LIMIT 1
                        """,
                ROW_MAPPER,
                conversationId
        ).stream().findFirst();
    }

    @Override
    public void attachConversationMessages(String turnId, String userMessageId, String assistantMessageId) {
        jdbc.update(
                """
                        UPDATE agent_turn
                           SET user_message_id = ?,
                               assistant_message_id = ?
                         WHERE turn_id = ?
                        """,
                userMessageId,
                assistantMessageId,
                turnId
        );
    }

    @Override
    public void recordIntent(String turnId, String intent, String source, Double confidence, String slotsJson) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(recordIntentSql(connection));
            statement.setString(1, intent);
            statement.setString(2, source);
            if (confidence == null) {
                statement.setBigDecimal(3, null);
            } else {
                statement.setBigDecimal(3, BigDecimal.valueOf(confidence));
            }
            statement.setString(4, defaultObjectJson(slotsJson));
            statement.setString(5, turnId);
            return statement;
        });
    }

    @Override
    public void recordToolState(String turnId, String toolsCalledJson, String cardsEmittedJson) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(recordToolStateSql(connection));
            statement.setString(1, defaultArrayJson(toolsCalledJson));
            statement.setString(2, defaultArrayJson(cardsEmittedJson));
            statement.setString(3, turnId);
            return statement;
        });
    }

    @Override
    public void markSucceeded(
            String turnId,
            String answerText,
            Boolean generatedByModel,
            Integer tokensIn,
            Integer tokensOut,
            Integer latencyMs,
            String memorySummary,
            Integer memorySummaryMessageCount,
            String memorySummaryModel
    ) {
        jdbc.update(
                """
                        UPDATE agent_turn
                           SET answer_text = ?,
                               generated_by_model = ?,
                               memory_summary = ?,
                               memory_summary_message_count = ?,
                               memory_summary_model = ?,
                               tokens_in = ?,
                               tokens_out = ?,
                               latency_ms = ?,
                               status = 'SUCCEEDED',
                               error_code = NULL,
                               error_message = NULL,
                               completed_at = now()
                         WHERE turn_id = ?
                           AND status = 'RUNNING'
                        """,
                answerText,
                generatedByModel,
                memorySummary,
                memorySummaryMessageCount,
                memorySummaryModel,
                tokensIn,
                tokensOut,
                latencyMs,
                turnId
        );
    }

    @Override
    public void markFailed(String turnId, String errorCode, String errorMessage, Integer latencyMs) {
        jdbc.update(
                """
                        UPDATE agent_turn
                           SET status = 'FAILED',
                               error_code = ?,
                               error_message = ?,
                               latency_ms = ?,
                               completed_at = now()
                         WHERE turn_id = ?
                           AND status = 'RUNNING'
                        """,
                errorCode,
                errorMessage,
                latencyMs,
                turnId
        );
    }

    private String recordIntentSql(Connection connection) throws SQLException {
        if (isPostgreSql(connection)) {
            return """
                    UPDATE agent_turn
                       SET intent = ?,
                           intent_source = ?,
                           intent_confidence = ?,
                           slots_json = CAST(? AS jsonb)
                     WHERE turn_id = ?
                    """;
        }
        return """
                UPDATE agent_turn
                   SET intent = ?,
                       intent_source = ?,
                       intent_confidence = ?,
                       slots_json = ?
                 WHERE turn_id = ?
                """;
    }

    private String recordToolStateSql(Connection connection) throws SQLException {
        if (isPostgreSql(connection)) {
            return """
                    UPDATE agent_turn
                       SET tools_called = CAST(? AS jsonb),
                           cards_emitted = CAST(? AS jsonb)
                     WHERE turn_id = ?
                    """;
        }
        return """
                UPDATE agent_turn
                   SET tools_called = ?,
                       cards_emitted = ?
                 WHERE turn_id = ?
                """;
    }

    private boolean isPostgreSql(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
    }
}
