package com.bytedance.ai.indexing.persistence.jdbc;

import com.bytedance.ai.indexing.persistence.RagIndexMessageFailureRepository;
import com.bytedance.ai.shared.support.RagJsonCodec;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基于 Spring JDBC 的离线索引消息失败审计仓储实现。
 */
@Repository
public class JdbcRagIndexMessageFailureRepository implements RagIndexMessageFailureRepository {

    private final JdbcTemplate jdbc;
    private final RagJsonCodec jsonCodec;

    public JdbcRagIndexMessageFailureRepository(JdbcTemplate jdbc, RagJsonCodec jsonCodec) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public void save(
            String messageId,
            String topic,
            int deliveryAttempt,
            String failureType,
            String errorMessage,
            String payloadBase64,
            String payloadPreview,
            Map<String, Object> propertiesJson
    ) {
        jdbc.update(
                """
                INSERT INTO rag_index_message_failures (
                    message_id, topic, delivery_attempt, failure_type, error_message,
                    payload_base64, payload_preview, properties_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                messageId,
                topic,
                deliveryAttempt,
                failureType,
                errorMessage,
                payloadBase64,
                payloadPreview,
                jsonCodec.write(propertiesJson)
        );
    }

    @Override
    public int countByMessageId(String messageId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_index_message_failures WHERE message_id = ?",
                Integer.class,
                messageId
        );
        return count == null ? 0 : count;
    }
}
