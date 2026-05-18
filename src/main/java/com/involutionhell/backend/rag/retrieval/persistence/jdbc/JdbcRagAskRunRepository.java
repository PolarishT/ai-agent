package com.involutionhell.backend.rag.retrieval.persistence.jdbc;

import com.involutionhell.backend.rag.retrieval.persistence.RagAskRunRecord;
import com.involutionhell.backend.rag.retrieval.persistence.RagAskRunRepository;
import com.involutionhell.backend.rag.retrieval.persistence.RagStaleAskRunRecord;
import com.involutionhell.backend.rag.shared.support.RagJsonCodec;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRagAskRunRepository implements RagAskRunRepository {

    private static final RowMapper<RagAskRunRecord> ROW_MAPPER = (rs, _) -> new RagAskRunRecord(
            rs.getLong("id"),
            rs.getString("run_id"),
            rs.getString("correlation_id"),
            rs.getString("user_id"),
            rs.getLong("conversation_id"),
            (Long) rs.getObject("user_message_id"),
            (Long) rs.getObject("assistant_message_id"),
            rs.getString("request_id"),
            rs.getString("question"),
            rs.getString("status"),
            rs.getString("error_code"),
            rs.getString("error_message")
    );

    private final JdbcTemplate jdbc;
    private final RagJsonCodec jsonCodec;

    public JdbcRagAskRunRepository(JdbcTemplate jdbc, RagJsonCodec jsonCodec) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public void createRunning(
            String runId,
            String correlationId,
            String userId,
            Long conversationId,
            Long userMessageId,
            Long assistantMessageId,
            String requestId,
            String question,
            Integer topK,
            Map<String, Object> filters
    ) {
        jdbc.update(
                """
                INSERT INTO rag_ask_runs (
                    run_id, correlation_id, user_id, conversation_id, user_message_id, assistant_message_id, request_id,
                    question, top_k, filters, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                runId,
                correlationId,
                userId,
                conversationId,
                userMessageId,
                assistantMessageId,
                requestId,
                question,
                topK,
                jsonCodec.write(filters),
                "RUNNING"
        );
    }

    @Override
    public Optional<RagAskRunRecord> findByRequestId(String userId, Long conversationId, String requestId) {
        return jdbc.query(
                """
                SELECT *
                  FROM rag_ask_runs
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
    public List<RagStaleAskRunRecord> findStaleRunning(OffsetDateTime startedBefore, int limit) {
        return jdbc.query(
                """
                  SELECT run_id, conversation_id, assistant_message_id, started_at
                    FROM rag_ask_runs
                   WHERE status = 'RUNNING'
                     AND started_at < ?
                   ORDER BY started_at ASC
                   LIMIT ?
                """,
                (rs, _) -> new RagStaleAskRunRecord(
                        rs.getString("run_id"),
                        rs.getLong("conversation_id"),
                        rs.getLong("assistant_message_id"),
                        toOffsetDateTime(rs.getTimestamp("started_at"))
                ),
                Timestamp.from(startedBefore.toInstant()),
                limit
        );
    }

    @Override
    public void markSucceeded(
            String runId,
            Long assistantMessageId,
            String retrievalQuestion,
            List<String> retrievalQueries,
            Object retrievedContexts,
            Object notices,
            boolean generatedByModel,
            boolean degraded
    ) {
        jdbc.update(
                """
                UPDATE rag_ask_runs
                   SET assistant_message_id = ?,
                       retrieval_question = ?,
                       retrieval_queries = ?,
                       retrieved_contexts = ?,
                       notices = ?,
                       generated_by_model = ?,
                       degraded = ?,
                       status = 'SUCCEEDED',
                       error_code = NULL,
                       error_message = NULL,
                       completed_at = now()
                 WHERE run_id = ?
                   AND status = 'RUNNING'
                """,
                assistantMessageId,
                retrievalQuestion,
                jsonCodec.write(retrievalQueries == null ? List.of() : retrievalQueries),
                jsonCodec.write(retrievedContexts == null ? List.of() : retrievedContexts),
                jsonCodec.write(notices == null ? List.of() : notices),
                generatedByModel,
                degraded,
                runId
        );
    }

    @Override
    public void markFailed(String runId, String errorCode, String errorMessage, Object notices) {
        jdbc.update(
                """
                UPDATE rag_ask_runs
                   SET notices = ?,
                       degraded = TRUE,
                       status = 'FAILED',
                       error_code = ?,
                       error_message = ?,
                       completed_at = now()
                 WHERE run_id = ?
                   AND status = 'RUNNING'
                """,
                jsonCodec.write(notices == null ? List.of() : notices),
                errorCode,
                errorMessage,
                runId
        );
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
