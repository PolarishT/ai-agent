package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC 实现：跨进程持久化 workflow runtime state，重启后能继续 WAITING_*。
 *
 * <p>表结构：{@code agent_workflow_state}（见 db/migration/schema.sql）。
 * conversation_id 是天然 unique key —— 一个会话同一时刻只能有一个进行中的 workflow。
 */
public class JdbcWorkflowStateStore implements WorkflowStateStore {

    private static final String WORKFLOW_KEY = "ecommerce-guide-v1";
    private static final int WORKFLOW_VERSION = 1;

    private final JdbcTemplate jdbc;
    private final RagJsonCodec jsonCodec;

    public JdbcWorkflowStateStore(JdbcTemplate jdbc, RagJsonCodec jsonCodec) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public WorkflowRuntimeState restore(String conversationId) {
        if (conversationId == null) {
            return new WorkflowRuntimeState();
        }
        try {
            String stateJson = jdbc.queryForObject(
                    "SELECT state_json FROM agent_workflow_state WHERE conversation_id = ?",
                    String.class,
                    conversationId);
            if (stateJson == null || stateJson.isBlank()) {
                return new WorkflowRuntimeState();
            }
            return jsonCodec.read(stateJson, WorkflowRuntimeStateSnapshot.class).toRuntimeState();
        } catch (EmptyResultDataAccessException ignored) {
            return new WorkflowRuntimeState();
        }
    }

    @Override
    public void save(String conversationId, WorkflowRuntimeState state) {
        if (conversationId == null || state == null) {
            return;
        }
        String stateJson = jsonCodec.write(WorkflowRuntimeStateSnapshot.from(state));
        String userId = state.userId() == null ? "" : state.userId();
        int updated = jdbc.update(
                """
                UPDATE agent_workflow_state
                SET status = ?, current_node = ?, state_json = ?::jsonb, version = ?, updated_at = now()
                WHERE conversation_id = ?
                """,
                state.status().name(),
                state.currentNode(),
                stateJson,
                WORKFLOW_VERSION,
                conversationId);
        if (updated > 0) {
            return;
        }
        jdbc.update(
                """
                INSERT INTO agent_workflow_state
                    (conversation_id, user_id, workflow_key, version, current_node, status, state_json)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (conversation_id) DO UPDATE SET
                    status = EXCLUDED.status,
                    current_node = EXCLUDED.current_node,
                    state_json = EXCLUDED.state_json,
                    version = EXCLUDED.version,
                    updated_at = now()
                """,
                conversationId,
                userId,
                WORKFLOW_KEY,
                WORKFLOW_VERSION,
                state.currentNode(),
                state.status().name(),
                stateJson);
    }

    @Override
    public void clear(String conversationId) {
        if (conversationId == null) {
            return;
        }
        jdbc.update("DELETE FROM agent_workflow_state WHERE conversation_id = ?", conversationId);
    }
}
