package com.bytedance.ai.graph.conversation.persistence.jdbc;

import com.bytedance.ai.graph.conversation.persistence.AgentConversationRepository;
import com.bytedance.ai.graph.conversation.ConversationMessage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAgentConversationRepository implements AgentConversationRepository {

    private static final RowMapper<ConversationMessage> MESSAGE_ROW_MAPPER = (rs, _) -> new ConversationMessage(
            rs.getLong("id"),
            rs.getString("message_id"),
            rs.getLong("conversation_id"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getString("status"),
            rs.getString("correlation_id"),
            rs.getInt("sequence_no"),
            toOffsetDateTime(rs.getTimestamp("created_at"))
    );

    private final JdbcTemplate jdbc;

    public JdbcAgentConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean existsConversation(String userId, String conversationId) {
        Integer count = jdbc.queryForObject(
                """
                        SELECT COUNT(*)
                          FROM agent_conversations
                         WHERE user_id = ?
                           AND conversation_id = ?
                        """,
                Integer.class,
                userId,
                conversationId
        );
        return count != null && count > 0;
    }

    @Override
    @Transactional
    public Long initConversation(String userId, String conversationId) {
        Optional<Long> existing = findConversationInternalId(userId, conversationId);
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        """
                                INSERT INTO agent_conversations (conversation_id, user_id, status, message_count)
                                VALUES (?, ?, 'ACTIVE', 0)
                                """,
                        new String[]{"id"}
                );
                statement.setString(1, conversationId);
                statement.setString(2, userId);
                return statement;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key != null) {
                return key.longValue();
            }
        } catch (DuplicateKeyException ignored) {
            // Another request created the row first; read it below.
        }

        return findConversationInternalId(userId, conversationId)
                .orElseThrow(() -> new IllegalStateException("agent conversation was not initialized"));
    }

    @Override
    public List<ConversationMessage> loadRecentMessages(String userId, String conversationId, int limit) {
        return jdbc.query(
                """
                        SELECT m.*
                          FROM agent_conversations c
                          JOIN agent_conversation_messages m
                            ON m.conversation_id = c.id
                         WHERE c.user_id = ?
                           AND c.conversation_id = ?
                         ORDER BY m.sequence_no DESC
                         LIMIT ?
                        """,
                MESSAGE_ROW_MAPPER,
                userId,
                conversationId,
                Math.max(limit, 0)
        );
    }

    @Override
    @Transactional
    public ConversationMessage saveUserMessage(
            String userId,
            String conversationId,
            String turnId,
            String correlationId,
            String content
    ) {
        return saveMessage(userId, conversationId, turnId, correlationId, "user", content, "SUCCEEDED");
    }

    @Override
    @Transactional
    public ConversationMessage saveAssistantMessage(
            String userId,
            String conversationId,
            String turnId,
            String correlationId,
            String content,
            String status
    ) {
        return saveMessage(userId, conversationId, turnId, correlationId, "assistant", content,
                StringUtils.hasText(status) ? status : "SUCCEEDED");
    }

    private ConversationMessage saveMessage(
            String userId,
            String conversationId,
            String turnId,
            String correlationId,
            String role,
            String content,
            String status
    ) {
        Long internalConversationId = findConversationInternalId(userId, conversationId)
                .orElseGet(() -> initConversation(userId, conversationId));
        int sequenceNo = nextSequenceNo(internalConversationId);
        String messageId = StringUtils.hasText(turnId)
                ? turnId + ":" + role + ":" + UUID.randomUUID()
                : UUID.randomUUID().toString();

        jdbc.update(
                """
                        INSERT INTO agent_conversation_messages (
                            message_id, conversation_id, role, content, status, correlation_id, sequence_no
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                messageId,
                internalConversationId,
                role,
                content,
                status,
                correlationId,
                sequenceNo
        );

        jdbc.update(
                """
                        UPDATE agent_conversations
                           SET message_count = message_count + 1,
                               last_message_at = CURRENT_TIMESTAMP,
                               updated_at = CURRENT_TIMESTAMP
                         WHERE id = ?
                        """,
                internalConversationId
        );

        return jdbc.query(
                """
                        SELECT *
                          FROM agent_conversation_messages
                         WHERE message_id = ?
                        """,
                MESSAGE_ROW_MAPPER,
                messageId
        ).stream().findFirst().orElseThrow();
    }

    @Override
    @Transactional
    public void createOrUpdateTurn(
            String userId,
            String conversationId,
            String turnId,
            String requestId,
            String status,
            String intent,
            String targetWorkflow
    ) {
        String storedStatus = normalizeTurnStatus(status);
        if (existsTurn(turnId)) {
            jdbc.update(
                    """
                            UPDATE agent_turn
                               SET request_id = ?,
                                   user_id = ?,
                                   conversation_id = ?,
                                   status = ?,
                                   intent = ?,
                                   target_workflow = ?,
                                   completed_at = CASE
                                       WHEN ? IN ('SUCCEEDED', 'FAILED', 'WAITING_CLARIFICATION', 'WAITING_CONFIRMATION')
                                       THEN CURRENT_TIMESTAMP
                                       ELSE completed_at
                                   END
                             WHERE turn_id = ?
                            """,
                    requestId,
                    userId,
                    conversationId,
                    storedStatus,
                    intent,
                    targetWorkflow,
                    storedStatus,
                    turnId
            );
            return;
        }

        jdbc.update(
                """
                        INSERT INTO agent_turn (
                            turn_id, request_id, conversation_id, user_id, status, intent, target_workflow
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                turnId,
                requestId,
                conversationId,
                userId,
                storedStatus,
                intent,
                targetWorkflow
        );
    }

    private String normalizeTurnStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return "PENDING";
        }
        return switch (status) {
            case "SUCCESS" -> "SUCCEEDED";
            default -> status;
        };
    }

    private Optional<Long> findConversationInternalId(String userId, String conversationId) {
        return jdbc.query(
                """
                        SELECT id
                          FROM agent_conversations
                         WHERE user_id = ?
                           AND conversation_id = ?
                        """,
                (rs, _) -> rs.getLong("id"),
                userId,
                conversationId
        ).stream().findFirst();
    }

    private int nextSequenceNo(Long internalConversationId) {
        Integer maxSequenceNo = jdbc.queryForObject(
                """
                        SELECT COALESCE(MAX(sequence_no), 0)
                          FROM agent_conversation_messages
                         WHERE conversation_id = ?
                        """,
                Integer.class,
                internalConversationId
        );
        return (maxSequenceNo == null ? 0 : maxSequenceNo) + 1;
    }

    private boolean existsTurn(String turnId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_turn WHERE turn_id = ?",
                Integer.class,
                turnId
        );
        return count != null && count > 0;
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
