package com.involutionhell.backend.rag.retrieval.persistence.jdbc;

import com.involutionhell.backend.rag.retrieval.persistence.RagConversationMessageRecord;
import com.involutionhell.backend.rag.retrieval.persistence.RagConversationMessageRepository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRagConversationMessageRepository implements RagConversationMessageRepository {

    private static final RowMapper<RagConversationMessageRecord> ROW_MAPPER = (rs, _) -> new RagConversationMessageRecord(
            rs.getLong("id"),
            rs.getString("message_id"),
            rs.getLong("conversation_id"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getString("status"),
            (Integer) rs.getObject("token_count"),
            rs.getString("correlation_id"),
            rs.getInt("sequence_no"),
            toOffsetDateTime(rs.getTimestamp("created_at"))
    );

    private final JdbcTemplate jdbc;

    public JdbcRagConversationMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RagConversationMessageRecord append(
            Long conversationId,
            String role,
            String content,
            String status,
            String correlationId
    ) {
        Integer nextSequence = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM rag_conversation_messages WHERE conversation_id = ?",
                Integer.class,
                conversationId
        );
        String messageId = "msg:" + UUID.randomUUID();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO rag_conversation_messages (
                        message_id, conversation_id, role, content, status, correlation_id, sequence_no
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, messageId);
            statement.setLong(2, conversationId);
            statement.setString(3, role);
            statement.setString(4, content);
            statement.setString(5, status);
            statement.setString(6, correlationId);
            statement.setInt(7, nextSequence == null ? 1 : nextSequence);
            return statement;
        }, keyHolder);
        Long id = generatedId(keyHolder, messageId);
        return findById(id);
    }

    @Override
    public List<RagConversationMessageRecord> findByConversationId(Long conversationId) {
        return jdbc.query(
                """
                SELECT * FROM rag_conversation_messages
                 WHERE conversation_id = ?
                 ORDER BY sequence_no ASC
                """,
                ROW_MAPPER,
                conversationId
        );
    }

    @Override
    public List<RagConversationMessageRecord> findRecentByConversationId(Long conversationId, int limit) {
        return jdbc.query(
                """
                SELECT * FROM (
                    SELECT * FROM rag_conversation_messages
                     WHERE conversation_id = ?
                     ORDER BY sequence_no DESC
                     LIMIT ?
                ) recent_messages
                 ORDER BY sequence_no ASC
                """,
                ROW_MAPPER,
                conversationId,
                limit
        );
    }

    @Override
    public RagConversationMessageRecord updateContentAndStatus(Long id, String content, String status) {
        jdbc.update(
                """
                UPDATE rag_conversation_messages
                   SET content = ?,
                       status = ?
                 WHERE id = ?
                """,
                content,
                status,
                id
        );
        return findById(id);
    }

    @Override
    public RagConversationMessageRecord findById(Long id) {
        return jdbc.queryForObject(
                "SELECT * FROM rag_conversation_messages WHERE id = ?",
                ROW_MAPPER,
                id
        );
    }

    private RagConversationMessageRecord findByMessageId(String messageId) {
        return jdbc.queryForObject(
                "SELECT * FROM rag_conversation_messages WHERE message_id = ?",
                ROW_MAPPER,
                messageId
        );
    }

    private Long generatedId(KeyHolder keyHolder, String messageId) {
        Map<String, Object> keys = keyHolder.getKeys();
        if (keys != null) {
            Object id = keys.get("ID");
            if (id == null) {
                id = keys.get("id");
            }
            if (id instanceof Number number) {
                return number.longValue();
            }
        }
        Number key = keyHolder.getKey();
        return key == null ? findByMessageId(messageId).id() : key.longValue();
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
