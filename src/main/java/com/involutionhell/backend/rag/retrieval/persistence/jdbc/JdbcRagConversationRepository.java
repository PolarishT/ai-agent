package com.involutionhell.backend.rag.retrieval.persistence.jdbc;

import com.involutionhell.backend.rag.retrieval.persistence.RagConversationCursor;
import com.involutionhell.backend.rag.retrieval.persistence.RagConversationPage;
import com.involutionhell.backend.rag.retrieval.persistence.RagConversationRecord;
import com.involutionhell.backend.rag.retrieval.persistence.RagConversationRepository;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class JdbcRagConversationRepository implements RagConversationRepository {

    private static final RowMapper<RagConversationRecord> ROW_MAPPER = (rs, _) -> new RagConversationRecord(
            rs.getLong("id"),
            rs.getString("conversation_id"),
            rs.getString("user_id"),
            rs.getString("title"),
            rs.getString("status"),
            rs.getInt("message_count"),
            toOffsetDateTime(rs.getTimestamp("created_at")),
            toOffsetDateTime(rs.getTimestamp("updated_at")),
            toOffsetDateTime(rs.getTimestamp("last_message_at"))
    );

    private final JdbcTemplate jdbc;

    public JdbcRagConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RagConversationRecord> findByConversationId(String conversationId) {
        return jdbc.query(
                "SELECT * FROM rag_conversations WHERE conversation_id = ?",
                ROW_MAPPER,
                conversationId
        ).stream().findFirst();
    }

    @Override
    public Optional<RagConversationRecord> findByUserIdAndConversationId(String userId, String conversationId) {
        return jdbc.query(
                "SELECT * FROM rag_conversations WHERE user_id = ? AND conversation_id = ?",
                ROW_MAPPER,
                userId,
                conversationId
        ).stream().findFirst();
    }

    @Override
    public RagConversationPage findByUserId(String userId, int limit, RagConversationCursor cursor) {
        List<Object> args = new ArrayList<>();
        args.add(userId);
        StringBuilder sql = new StringBuilder("""
                SELECT *
                  FROM rag_conversations
                 WHERE user_id = ?
                   AND status <> 'DELETED'
                """);
        if (cursor != null && cursor.id() != null) {
            if (cursor.lastMessageAt() == null) {
                sql.append(" AND last_message_at IS NULL AND id < ?");
                args.add(cursor.id());
            } else {
                sql.append("""
                         AND (
                              last_message_at < ?
                           OR (last_message_at = ? AND id < ?)
                           OR last_message_at IS NULL
                         )
                        """);
                Timestamp timestamp = Timestamp.from(cursor.lastMessageAt().toInstant());
                args.add(timestamp);
                args.add(timestamp);
                args.add(cursor.id());
            }
        }
        sql.append("""
                 ORDER BY last_message_at DESC NULLS LAST, id DESC
                 LIMIT ?
                """);
        args.add(limit + 1);

        List<RagConversationRecord> rows = jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
        RagConversationCursor nextCursor = null;
        if (rows.size() > limit) {
            RagConversationRecord last = rows.get(limit - 1);
            nextCursor = new RagConversationCursor(last.lastMessageAt(), last.id());
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        return new RagConversationPage(rows, nextCursor);
    }

    @Override
    public RagConversationRecord update(String userId, String conversationId, String title, String status) {
        List<String> assignments = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (title != null) {
            assignments.add("title = ?");
            args.add(title);
        }
        if (status != null) {
            assignments.add("status = ?");
            args.add(status);
        }
        if (assignments.isEmpty()) {
            return findByUserIdAndConversationId(userId, conversationId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        }
        assignments.add("updated_at = now()");
        String sql = "UPDATE rag_conversations SET " + String.join(", ", assignments)
                + " WHERE user_id = ? AND conversation_id = ?";
        args.add(userId);
        args.add(conversationId);
        int updated = jdbc.update(sql, args.toArray());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        return findByUserIdAndConversationId(userId, conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    @Override
    public void lockById(Long id) {
        jdbc.queryForObject("SELECT id FROM rag_conversations WHERE id = ? FOR UPDATE", Long.class, id);
    }

    @Override
    public void refreshStats(Long id) {
        jdbc.update(
                """
                UPDATE rag_conversations
                   SET message_count = (
                           SELECT COUNT(*) FROM rag_conversation_messages WHERE conversation_id = ?
                       ),
                       last_message_at = (
                           SELECT MAX(created_at) FROM rag_conversation_messages WHERE conversation_id = ?
                       ),
                       updated_at = now()
                 WHERE id = ?
                """,
                id,
                id,
                id
        );
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
