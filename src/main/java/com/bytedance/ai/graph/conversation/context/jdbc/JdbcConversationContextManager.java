package com.bytedance.ai.graph.conversation.context.jdbc;

import com.bytedance.ai.graph.conversation.ConversationMessage;
import com.bytedance.ai.graph.conversation.context.ConversationContextItemStatus;
import com.bytedance.ai.graph.conversation.context.ConversationContextItemType;
import com.bytedance.ai.graph.conversation.context.ConversationContextManager;
import com.bytedance.ai.graph.conversation.context.ConversationRuntimeContext;
import com.bytedance.ai.graph.conversation.persistence.AgentConversationRepository;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC-backed central conversation context manager.
 */
@Service
public class JdbcConversationContextManager implements ConversationContextManager {

    private static final String KEY_CURRENT = "current";

    /** 任务链 CAS 写入的乐观重试次数上限：并发 turn 抢占活跃行时重新加载再应用。 */
    private static final int MAX_TASK_CHAIN_CAS_RETRIES = 3;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AgentConversationRepository conversationRepository;
    private final RagJsonCodec jsonCodec;

    public JdbcConversationContextManager(
            NamedParameterJdbcTemplate jdbcTemplate,
            AgentConversationRepository conversationRepository,
            RagJsonCodec jsonCodec
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.conversationRepository = conversationRepository;
        this.jsonCodec = jsonCodec;
    }

    @Override
    @Transactional
    public ConversationRuntimeContext load(String userId, String conversationId) {
        Long conversationInternalId = conversationRepository.initConversation(userId, conversationId);
        expireStaleItems(conversationInternalId);
        List<ConversationMessage> recentMessages = new ArrayList<>(
                conversationRepository.loadRecentMessages(userId, conversationId, 24)
        );
        recentMessages.sort(Comparator.comparingInt(ConversationMessage::sequenceNo));

        List<ContextItemRow> rows = activeRows(conversationInternalId);
        if (rows.isEmpty()) {
            return ConversationRuntimeContext.empty(conversationInternalId, userId, conversationId, recentMessages);
        }

        ConversationRuntimeContext.Focus focus = null;
        List<ConversationRuntimeContext.ProductCandidateItem> productCandidates = new ArrayList<>();
        ConversationRuntimeContext.CartSnapshot cart = null;
        ConversationRuntimeContext.OrderContext order = null;
        ConversationRuntimeContext.PendingClarification pendingClarification = null;
        ConversationRuntimeContext.LastTurn currentLastTurn = null;
        Map<String, ConversationRuntimeContext.LastTurn> lastResults = new LinkedHashMap<>();
        Map<String, Object> memorySlots = new LinkedHashMap<>();
        List<ConversationRuntimeContext.TaskChain> taskChains = new ArrayList<>();

        for (ContextItemRow row : rows) {
            Map<String, Object> payload = payload(row);
            switch (row.itemType()) {
                case PRODUCT_CANDIDATE -> productCandidates.add(toProductCandidate(row, payload));
                case FOCUS -> {
                    if (KEY_CURRENT.equals(row.itemKey())) {
                        focus = toFocus(row, payload);
                    }
                }
                case CART_SNAPSHOT -> {
                    if (KEY_CURRENT.equals(row.itemKey())) {
                        cart = new ConversationRuntimeContext.CartSnapshot(row.id(), payload);
                    }
                }
                case ORDER_CONTEXT -> {
                    if (KEY_CURRENT.equals(row.itemKey())) {
                        order = toOrderContext(row, payload);
                    }
                }
                case PENDING_CLARIFICATION -> {
                    if (KEY_CURRENT.equals(row.itemKey())) {
                        pendingClarification = toPendingClarification(row, payload);
                    }
                }
                case LAST_RESULT -> {
                    ConversationRuntimeContext.LastTurn lastTurn = toLastTurn(row, payload);
                    lastResults.put(row.itemKey(), lastTurn);
                    if (KEY_CURRENT.equals(row.itemKey())) {
                        currentLastTurn = lastTurn;
                    }
                }
                case MEMORY_SLOT -> memorySlots.put(row.itemKey(), payload.getOrDefault("value", payload));
                case TASK_CHAIN -> taskChains.add(toTaskChain(row, payload));
            }
        }

        productCandidates.sort(Comparator.comparingInt(ConversationRuntimeContext.ProductCandidateItem::rank));
        taskChains.sort(Comparator.comparing(ConversationRuntimeContext.TaskChain::createdAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return new ConversationRuntimeContext(
                conversationInternalId,
                userId,
                conversationId,
                recentMessages,
                focus,
                productCandidates,
                cart,
                order,
                pendingClarification,
                currentLastTurn,
                lastResults,
                memorySlots,
                taskChains
        );
    }

    @Override
    @Transactional
    public void saveProductCandidates(
            String userId,
            String conversationId,
            String sourceTurnId,
            String sourceWorkflow,
            List<ConversationRuntimeContext.ProductCandidateItem> candidates,
            LocalDateTime expiresAt
    ) {
        Long conversationInternalId = conversationRepository.initConversation(userId, conversationId);
        supersedeActive(conversationInternalId, ConversationContextItemType.PRODUCT_CANDIDATE, null);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (ConversationRuntimeContext.ProductCandidateItem candidate : candidates) {
            int rank = candidate.rank() <= 0 ? 1 : candidate.rank();
            insert(
                    conversationInternalId,
                    userId,
                    conversationId,
                    ConversationContextItemType.PRODUCT_CANDIDATE,
                    "rank:" + rank,
                    sourceTurnId,
                    sourceWorkflow,
                    ConversationContextItemStatus.ACTIVE,
                    productCandidatePayload(candidate, rank),
                    expiresAt
            );
        }
    }

    @Override
    @Transactional
    public void updateFocus(
            String userId,
            String conversationId,
            String sourceTurnId,
            String sourceWorkflow,
            ConversationRuntimeContext.Focus focus,
            LocalDateTime expiresAt
    ) {
        Long conversationInternalId = conversationRepository.initConversation(userId, conversationId);
        supersedeActive(conversationInternalId, ConversationContextItemType.FOCUS, KEY_CURRENT);
        if (focus == null) {
            return;
        }
        insert(
                conversationInternalId,
                userId,
                conversationId,
                ConversationContextItemType.FOCUS,
                KEY_CURRENT,
                sourceTurnId,
                sourceWorkflow,
                ConversationContextItemStatus.ACTIVE,
                focusPayload(focus),
                expiresAt
        );
    }

    @Override
    @Transactional
    public ConversationRuntimeContext.PendingClarification savePendingClarification(
            String userId,
            String conversationId,
            String sourceTurnId,
            String sourceWorkflow,
            ConversationRuntimeContext.PendingClarification clarification,
            LocalDateTime expiresAt
    ) {
        Long conversationInternalId = conversationRepository.initConversation(userId, conversationId);
        supersedeActive(conversationInternalId, ConversationContextItemType.PENDING_CLARIFICATION, KEY_CURRENT);
        Long id = insert(
                conversationInternalId,
                userId,
                conversationId,
                ConversationContextItemType.PENDING_CLARIFICATION,
                KEY_CURRENT,
                sourceTurnId,
                sourceWorkflow,
                ConversationContextItemStatus.ACTIVE,
                pendingClarificationPayload(clarification),
                expiresAt
        );
        return new ConversationRuntimeContext.PendingClarification(
                id,
                clarification.clarificationType(),
                clarification.sourceWorkflow(),
                clarification.quantity(),
                clarification.candidates(),
                expiresAt,
                clarification.payload()
        );
    }

    @Override
    @Transactional
    public void consumePendingClarification(Long contextItemId) {
        markContextItemStatus(contextItemId, ConversationContextItemStatus.CONSUMED);
    }

    @Override
    @Transactional
    public void updateCartSnapshot(
            String userId,
            String conversationId,
            String sourceTurnId,
            String sourceWorkflow,
            ConversationRuntimeContext.CartSnapshot cartSnapshot,
            LocalDateTime expiresAt
    ) {
        Long conversationInternalId = conversationRepository.initConversation(userId, conversationId);
        supersedeActive(conversationInternalId, ConversationContextItemType.CART_SNAPSHOT, KEY_CURRENT);
        if (cartSnapshot == null) {
            return;
        }
        insert(
                conversationInternalId,
                userId,
                conversationId,
                ConversationContextItemType.CART_SNAPSHOT,
                KEY_CURRENT,
                sourceTurnId,
                sourceWorkflow,
                ConversationContextItemStatus.ACTIVE,
                cartSnapshot.payload(),
                expiresAt
        );
    }

    @Override
    @Transactional
    public ConversationRuntimeContext.OrderContext updateOrderContext(
            String userId,
            String conversationId,
            String sourceTurnId,
            String sourceWorkflow,
            ConversationRuntimeContext.OrderContext orderContext,
            LocalDateTime expiresAt
    ) {
        Long conversationInternalId = conversationRepository.initConversation(userId, conversationId);
        supersedeActive(conversationInternalId, ConversationContextItemType.ORDER_CONTEXT, KEY_CURRENT);
        Long id = insert(
                conversationInternalId,
                userId,
                conversationId,
                ConversationContextItemType.ORDER_CONTEXT,
                KEY_CURRENT,
                sourceTurnId,
                sourceWorkflow,
                ConversationContextItemStatus.ACTIVE,
                orderPayload(orderContext),
                expiresAt
        );
        return copyOrderWithId(orderContext, id, expiresAt);
    }

    @Override
    @Transactional
    public void updateLastTurn(
            String userId,
            String conversationId,
            String sourceTurnId,
            String sourceWorkflow,
            ConversationRuntimeContext.LastTurn lastTurn,
            LocalDateTime expiresAt
    ) {
        Long conversationInternalId = conversationRepository.initConversation(userId, conversationId);
        writeLastTurn(conversationInternalId, userId, conversationId, KEY_CURRENT, sourceTurnId, sourceWorkflow, lastTurn, expiresAt);
        if (StringUtils.hasText(sourceWorkflow)) {
            writeLastTurn(conversationInternalId, userId, conversationId, sourceWorkflow, sourceTurnId,
                    sourceWorkflow, lastTurn, expiresAt);
        }
    }

    @Override
    @Transactional
    public boolean transitionOrderContextStatus(
            Long contextItemId,
            String expectedOrderStatus,
            ConversationRuntimeContext.OrderContext orderContext,
            ConversationContextItemStatus itemStatus
    ) {
        if (contextItemId == null || orderContext == null) {
            return false;
        }
        String sql = """
                UPDATE public.agent_context_items
                   SET payload_json = CAST(:payload AS jsonb),
                       status = :status,
                       updated_at = NOW()
                 WHERE id = :id
                   AND item_type = 'ORDER_CONTEXT'
                   AND status = 'ACTIVE'
                   AND payload_json ->> 'orderStatus' = :expectedOrderStatus
                """;
        int updated = jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", contextItemId)
                .addValue("payload", jsonCodec.write(orderPayload(orderContext)))
                .addValue("status", itemStatus.name())
                .addValue("expectedOrderStatus", expectedOrderStatus));
        return updated == 1;
    }

    @Override
    @Transactional
    public void markContextItemStatus(Long contextItemId, ConversationContextItemStatus status) {
        if (contextItemId == null || status == null) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE public.agent_context_items
                   SET status = :status,
                       updated_at = NOW()
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", contextItemId)
                .addValue("status", status.name()));
    }

    @Override
    @Transactional
    public void saveTaskChain(
            String userId,
            String conversationId,
            String sourceTurnId,
            String sourceWorkflow,
            ConversationRuntimeContext.TaskChain taskChain,
            LocalDateTime expiresAt
    ) {
        if (taskChain == null || !StringUtils.hasText(taskChain.taskChainId())) {
            return;
        }
        Long conversationInternalId = conversationRepository.initConversation(userId, conversationId);
        supersedeActive(conversationInternalId, ConversationContextItemType.TASK_CHAIN, taskChain.taskChainId());
        insert(
                conversationInternalId,
                userId,
                conversationId,
                ConversationContextItemType.TASK_CHAIN,
                taskChain.taskChainId(),
                sourceTurnId,
                sourceWorkflow,
                ConversationContextItemStatus.ACTIVE,
                taskChainPayload(taskChain),
                expiresAt
        );
    }

    @Override
    public ConversationRuntimeContext.TaskChain loadTaskChain(
            String userId,
            String conversationId,
            String taskChainId
    ) {
        if (!StringUtils.hasText(taskChainId)) {
            return null;
        }
        Long conversationInternalId = conversationRepository.initConversation(userId, conversationId);
        String sql = """
                SELECT *
                  FROM public.agent_context_items
                 WHERE conversation_internal_id = :conv
                   AND item_type = 'TASK_CHAIN'
                   AND item_key = :chainId
                   AND status = 'ACTIVE'
                   AND (expires_at IS NULL OR expires_at > NOW())
                 ORDER BY id DESC
                 LIMIT 1
                """;
        List<ContextItemRow> rows = jdbcTemplate.query(sql,
                new MapSqlParameterSource()
                        .addValue("conv", conversationInternalId)
                        .addValue("chainId", taskChainId),
                rowMapper());
        if (rows.isEmpty()) {
            return null;
        }
        ContextItemRow row = rows.get(0);
        return toTaskChain(row, payload(row));
    }

    @Override
    @Transactional
    public boolean markPlanTask(
            String userId,
            String conversationId,
            String taskChainId,
            String taskId,
            String newTaskStatus,
            ConversationRuntimeContext.TaskStep executedStep,
            String turnId
    ) {
        for (int attempt = 0; attempt < MAX_TASK_CHAIN_CAS_RETRIES; attempt++) {
            ConversationRuntimeContext.TaskChain chain = loadTaskChain(userId, conversationId, taskChainId);
            if (chain == null) {
                return false;
            }
            List<ConversationRuntimeContext.PlanTask> newPlan = new ArrayList<>(chain.planTasks().size());
            boolean found = false;
            for (ConversationRuntimeContext.PlanTask t : chain.planTasks()) {
                if (t.taskId() != null && t.taskId().equals(taskId)) {
                    newPlan.add(new ConversationRuntimeContext.PlanTask(
                            t.taskId(), t.taskName(), t.taskType(), t.workflow(), t.order(),
                            StringUtils.hasText(newTaskStatus) ? newTaskStatus : t.status()
                    ));
                    found = true;
                } else {
                    newPlan.add(t);
                }
            }
            if (!found) {
                return false;
            }
            List<ConversationRuntimeContext.TaskStep> newSteps = new ArrayList<>(chain.steps());
            if (executedStep != null) {
                newSteps.add(executedStep);
            }
            LocalDateTime now = LocalDateTime.now();
            if (replaceActiveTaskChain(userId, conversationId, turnId, null, chain.contextItemId(),
                    rebuildChain(chain, chain.status(), newPlan, newSteps, turnId, now), null)) {
                return true;
            }
            // CAS 落败：并发 turn 抢先替换了活跃行 → 重新加载最新 chain 再应用
        }
        return false;
    }

    @Override
    @Transactional
    public boolean transitionChainStatus(
            String userId,
            String conversationId,
            String taskChainId,
            String newChainStatus,
            String turnId
    ) {
        if (!StringUtils.hasText(newChainStatus)) {
            return false;
        }
        for (int attempt = 0; attempt < MAX_TASK_CHAIN_CAS_RETRIES; attempt++) {
            ConversationRuntimeContext.TaskChain chain = loadTaskChain(userId, conversationId, taskChainId);
            if (chain == null) {
                return false;
            }
            LocalDateTime now = LocalDateTime.now();
            if (replaceActiveTaskChain(userId, conversationId, turnId, null, chain.contextItemId(),
                    rebuildChain(chain, newChainStatus, chain.planTasks(), chain.steps(), turnId, now), null)) {
                return true;
            }
            // CAS 落败：并发 turn 抢先替换了活跃行 → 重新加载最新 chain 再应用
        }
        return false;
    }

    private ConversationRuntimeContext.TaskChain rebuildChain(
            ConversationRuntimeContext.TaskChain chain,
            String status,
            List<ConversationRuntimeContext.PlanTask> planTasks,
            List<ConversationRuntimeContext.TaskStep> steps,
            String turnId,
            LocalDateTime now
    ) {
        return new ConversationRuntimeContext.TaskChain(
                null,
                chain.taskChainId(),
                chain.schemaVersion(),
                status,
                chain.userGoal(),
                planTasks,
                steps,
                chain.createdTurnId(),
                StringUtils.hasText(turnId) ? turnId : chain.lastUpdatedTurnId(),
                chain.createdAt() == null ? now : chain.createdAt(),
                now
        );
    }

    private void writeLastTurn(
            Long conversationInternalId,
            String userId,
            String conversationId,
            String itemKey,
            String sourceTurnId,
            String sourceWorkflow,
            ConversationRuntimeContext.LastTurn lastTurn,
            LocalDateTime expiresAt
    ) {
        supersedeActive(conversationInternalId, ConversationContextItemType.LAST_RESULT, itemKey);
        if (lastTurn == null) {
            return;
        }
        insert(
                conversationInternalId,
                userId,
                conversationId,
                ConversationContextItemType.LAST_RESULT,
                itemKey,
                sourceTurnId,
                sourceWorkflow,
                ConversationContextItemStatus.ACTIVE,
                lastTurnPayload(lastTurn),
                expiresAt
        );
    }

    private Long insert(
            Long conversationInternalId,
            String userId,
            String conversationId,
            ConversationContextItemType itemType,
            String itemKey,
            String sourceTurnId,
            String sourceWorkflow,
            ConversationContextItemStatus status,
            Map<String, Object> payload,
            LocalDateTime expiresAt
    ) {
        String sql = """
                INSERT INTO public.agent_context_items (
                    conversation_internal_id, user_id, conversation_id, item_type, item_key,
                    source_turn_id, source_workflow, status, payload_json, expires_at
                )
                VALUES (
                    :conversationInternalId, :userId, :conversationId, :itemType, :itemKey,
                    :sourceTurnId, :sourceWorkflow, :status, CAST(:payload AS jsonb), :expiresAt
                )
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("conversationInternalId", conversationInternalId)
                .addValue("userId", userId)
                .addValue("conversationId", conversationId)
                .addValue("itemType", itemType.name())
                .addValue("itemKey", itemKey)
                .addValue("sourceTurnId", sourceTurnId)
                .addValue("sourceWorkflow", sourceWorkflow)
                .addValue("status", status.name())
                .addValue("payload", jsonCodec.write(payload == null ? Map.of() : payload))
                .addValue("expiresAt", expiresAt), keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("agent context item insert did not return id");
        }
        return key.longValue();
    }

    private void supersedeActive(
            Long conversationInternalId,
            ConversationContextItemType itemType,
            String itemKey
    ) {
        String sql = """
                UPDATE public.agent_context_items
                   SET status = 'SUPERSEDED',
                       updated_at = NOW()
                 WHERE conversation_internal_id = :conversationInternalId
                   AND item_type = :itemType
                   AND status = 'ACTIVE'
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("conversationInternalId", conversationInternalId)
                .addValue("itemType", itemType.name());
        if (StringUtils.hasText(itemKey)) {
            sql += " AND item_key = :itemKey";
            params.addValue("itemKey", itemKey);
        }
        jdbcTemplate.update(sql, params);
    }

    /**
     * 按读到的旧活跃行 id 做乐观 CAS 替换任务链：仅当该行仍为 ACTIVE 时把它 SUPERSEDE 掉并插入新版本。
     * 若 CAS 命中 0 行（已被并发 turn 抢先替换 / 过期），返回 false 且不写入——由上层重新加载后重试。
     * 配合 uq_agent_context_items_active_key 唯一索引，保证任一时刻同一 chain 只有一行 ACTIVE。
     */
    private boolean replaceActiveTaskChain(
            String userId,
            String conversationId,
            String sourceTurnId,
            String sourceWorkflow,
            Long expectedActiveId,
            ConversationRuntimeContext.TaskChain taskChain,
            LocalDateTime expiresAt
    ) {
        if (expectedActiveId == null || taskChain == null || !StringUtils.hasText(taskChain.taskChainId())) {
            return false;
        }
        Long conversationInternalId = conversationRepository.initConversation(userId, conversationId);
        if (supersedeActiveById(expectedActiveId) != 1) {
            return false;
        }
        insert(
                conversationInternalId,
                userId,
                conversationId,
                ConversationContextItemType.TASK_CHAIN,
                taskChain.taskChainId(),
                sourceTurnId,
                sourceWorkflow,
                ConversationContextItemStatus.ACTIVE,
                taskChainPayload(taskChain),
                expiresAt
        );
        return true;
    }

    private int supersedeActiveById(Long activeId) {
        return jdbcTemplate.update("""
                UPDATE public.agent_context_items
                   SET status = 'SUPERSEDED',
                       updated_at = NOW()
                 WHERE id = :id
                   AND status = 'ACTIVE'
                """, new MapSqlParameterSource("id", activeId));
    }

    private void expireStaleItems(Long conversationInternalId) {
        jdbcTemplate.update("""
                UPDATE public.agent_context_items
                   SET status = 'EXPIRED',
                       updated_at = NOW()
                 WHERE conversation_internal_id = :conversationInternalId
                   AND status = 'ACTIVE'
                   AND expires_at IS NOT NULL
                   AND expires_at <= NOW()
                """, new MapSqlParameterSource("conversationInternalId", conversationInternalId));
    }

    private List<ContextItemRow> activeRows(Long conversationInternalId) {
        String sql = """
                SELECT *
                  FROM public.agent_context_items
                 WHERE conversation_internal_id = :conversationInternalId
                   AND status = 'ACTIVE'
                   AND (expires_at IS NULL OR expires_at > NOW())
                 ORDER BY created_at ASC, id ASC
                """;
        return jdbcTemplate.query(sql,
                new MapSqlParameterSource("conversationInternalId", conversationInternalId),
                rowMapper());
    }

    private RowMapper<ContextItemRow> rowMapper() {
        return (rs, _) -> new ContextItemRow(
                rs.getLong("id"),
                rs.getLong("conversation_internal_id"),
                rs.getString("user_id"),
                rs.getString("conversation_id"),
                ConversationContextItemType.valueOf(rs.getString("item_type")),
                rs.getString("item_key"),
                rs.getString("source_turn_id"),
                rs.getString("source_workflow"),
                ConversationContextItemStatus.valueOf(rs.getString("status")),
                rs.getString("payload_json"),
                toLocalDateTime(rs, "expires_at")
        );
    }

    private Map<String, Object> payload(ContextItemRow row) {
        if (!StringUtils.hasText(row.payloadJson())) {
            return new LinkedHashMap<>();
        }
        try {
            return jsonCodec.readMap(row.payloadJson());
        } catch (RuntimeException exception) {
            try {
                String unwrapped = jsonCodec.read(row.payloadJson(), String.class);
                if (StringUtils.hasText(unwrapped)) {
                    return jsonCodec.readMap(unwrapped);
                }
            } catch (RuntimeException ignored) {
                // fall through to empty payload
            }
            return new LinkedHashMap<>();
        }
    }

    private ConversationRuntimeContext.ProductCandidateItem toProductCandidate(
            ContextItemRow row,
            Map<String, Object> payload
    ) {
        return new ConversationRuntimeContext.ProductCandidateItem(
                row.id(),
                intValue(payload.get("rank"), rankFromKey(row.itemKey())),
                stringValue(payload.get("productId")),
                stringValue(payload.get("skuId")),
                stringValue(payload.get("productName")),
                decimalValue(payload.get("price")),
                stringValue(payload.get("brief")),
                stringValue(payload.get("spec")),
                stringValue(payload.get("externalRef")),
                integerValue(payload.get("stock")),
                payload
        );
    }

    private ConversationRuntimeContext.Focus toFocus(ContextItemRow row, Map<String, Object> payload) {
        return new ConversationRuntimeContext.Focus(
                row.id(),
                row.itemKey(),
                integerValue(payload.get("rank")),
                stringValue(payload.get("productId")),
                stringValue(payload.get("skuId")),
                stringValue(payload.get("productName")),
                payload
        );
    }

    private ConversationRuntimeContext.OrderContext toOrderContext(ContextItemRow row, Map<String, Object> payload) {
        return new ConversationRuntimeContext.OrderContext(
                row.id(),
                mapValue(payload.get("cartSnapshot")),
                stringValue(payload.get("cartSnapshotHash")),
                mapValue(payload.get("addressSnapshot")),
                decimalValue(payload.get("amountSnapshot")),
                stringValue(payload.get("orderStatus")),
                stringValue(payload.get("failReason")),
                stringValue(payload.get("orderNo")),
                row.expiresAt(),
                payload
        );
    }

    private ConversationRuntimeContext.PendingClarification toPendingClarification(
            ContextItemRow row,
            Map<String, Object> payload
    ) {
        List<ConversationRuntimeContext.ProductCandidateItem> candidates = new ArrayList<>();
        Object rawCandidates = payload.get("candidates");
        if (rawCandidates instanceof List<?> list) {
            for (Object value : list) {
                Map<String, Object> candidatePayload = mapValue(value);
                candidates.add(toProductCandidate(row, candidatePayload));
            }
        }
        candidates.sort(Comparator.comparingInt(ConversationRuntimeContext.ProductCandidateItem::rank));
        return new ConversationRuntimeContext.PendingClarification(
                row.id(),
                stringValue(payload.get("clarificationType")),
                stringValue(payload.getOrDefault("sourceWorkflow", row.sourceWorkflow())),
                integerValue(payload.get("quantity")),
                candidates,
                row.expiresAt(),
                payload
        );
    }

    private ConversationRuntimeContext.LastTurn toLastTurn(ContextItemRow row, Map<String, Object> payload) {
        return new ConversationRuntimeContext.LastTurn(
                row.id(),
                stringValue(payload.getOrDefault("sourceWorkflow", row.sourceWorkflow())),
                stringValue(payload.get("status")),
                payload
        );
    }

    private ConversationRuntimeContext.TaskChain toTaskChain(ContextItemRow row, Map<String, Object> payload) {
        ConversationRuntimeContext.UserGoal userGoal = toUserGoal(mapValue(payload.get("userGoal")));
        List<ConversationRuntimeContext.PlanTask> planTasks = new ArrayList<>();
        Object rawPlan = payload.get("planTasks");
        if (rawPlan instanceof List<?> list) {
            for (Object value : list) {
                planTasks.add(toPlanTask(mapValue(value)));
            }
        }
        List<ConversationRuntimeContext.TaskStep> steps = new ArrayList<>();
        Object rawSteps = payload.get("steps");
        if (rawSteps instanceof List<?> list) {
            for (Object value : list) {
                steps.add(toTaskStep(mapValue(value)));
            }
        }
        return new ConversationRuntimeContext.TaskChain(
                row.id(),
                stringValue(payload.get("taskChainId")),
                intValue(payload.get("schemaVersion"), 1),
                stringValue(payload.get("status")),
                userGoal,
                planTasks,
                steps,
                stringValue(payload.get("createdTurnId")),
                stringValue(payload.get("lastUpdatedTurnId")),
                parseDateTime(payload.get("createdAt")),
                parseDateTime(payload.get("updatedAt"))
        );
    }

    private ConversationRuntimeContext.UserGoal toUserGoal(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        return new ConversationRuntimeContext.UserGoal(
                stringValue(payload.get("goalType")),
                stringValue(payload.get("goalText")),
                Boolean.TRUE.equals(payload.get("isComposite")),
                stringValue(payload.get("status"))
        );
    }

    private ConversationRuntimeContext.PlanTask toPlanTask(Map<String, Object> payload) {
        return new ConversationRuntimeContext.PlanTask(
                stringValue(payload.get("taskId")),
                stringValue(payload.get("taskName")),
                stringValue(payload.get("taskType")),
                stringValue(payload.get("workflow")),
                intValue(payload.get("order"), 0),
                stringValue(payload.get("status"))
        );
    }

    private ConversationRuntimeContext.TaskStep toTaskStep(Map<String, Object> payload) {
        Object rawOutput = payload.get("output");
        Map<String, Object> output = rawOutput instanceof Map<?, ?> ? mapValue(rawOutput) : Map.of();
        return new ConversationRuntimeContext.TaskStep(
                intValue(payload.get("stepNo"), 0),
                stringValue(payload.get("stepId")),
                stringValue(payload.get("taskType")),
                stringValue(payload.get("taskText")),
                stringValue(payload.get("workflow")),
                stringValue(payload.get("status")),
                stringValue(payload.get("turnId")),
                parseDateTime(payload.get("startedAt")),
                parseDateTime(payload.get("completedAt")),
                output
        );
    }

    private Map<String, Object> productCandidatePayload(
            ConversationRuntimeContext.ProductCandidateItem candidate,
            int rank
    ) {
        Map<String, Object> payload = new LinkedHashMap<>(candidate.payload());
        payload.put("rank", rank);
        putIfPresent(payload, "productId", candidate.productId());
        putIfPresent(payload, "skuId", candidate.skuId());
        putIfPresent(payload, "productName", candidate.productName());
        putIfPresent(payload, "price", candidate.price());
        putIfPresent(payload, "brief", candidate.brief());
        putIfPresent(payload, "spec", candidate.spec());
        putIfPresent(payload, "externalRef", candidate.externalRef());
        putIfPresent(payload, "stock", candidate.stock());
        return payload;
    }

    private Map<String, Object> focusPayload(ConversationRuntimeContext.Focus focus) {
        Map<String, Object> payload = new LinkedHashMap<>(focus.payload());
        putIfPresent(payload, "rank", focus.rank());
        putIfPresent(payload, "productId", focus.productId());
        putIfPresent(payload, "skuId", focus.skuId());
        putIfPresent(payload, "productName", focus.productName());
        return payload;
    }

    private Map<String, Object> pendingClarificationPayload(
            ConversationRuntimeContext.PendingClarification clarification
    ) {
        Map<String, Object> payload = new LinkedHashMap<>(clarification.payload());
        putIfPresent(payload, "clarificationType", clarification.clarificationType());
        putIfPresent(payload, "sourceWorkflow", clarification.sourceWorkflow());
        putIfPresent(payload, "quantity", clarification.quantity());
        List<Map<String, Object>> candidates = clarification.candidates().stream()
                .map(candidate -> productCandidatePayload(candidate, candidate.rank()))
                .toList();
        payload.put("candidates", candidates);
        return payload;
    }

    private Map<String, Object> orderPayload(ConversationRuntimeContext.OrderContext orderContext) {
        Map<String, Object> payload = new LinkedHashMap<>(orderContext.payload());
        payload.put("cartSnapshot", orderContext.cartSnapshot());
        putIfPresent(payload, "cartSnapshotHash", orderContext.cartSnapshotHash());
        payload.put("addressSnapshot", orderContext.addressSnapshot());
        putIfPresent(payload, "amountSnapshot", orderContext.amountSnapshot());
        putIfPresent(payload, "orderStatus", orderContext.orderStatus());
        putIfPresent(payload, "failReason", orderContext.failReason());
        putIfPresent(payload, "orderNo", orderContext.orderNo());
        return payload;
    }

    private Map<String, Object> lastTurnPayload(ConversationRuntimeContext.LastTurn lastTurn) {
        Map<String, Object> payload = new LinkedHashMap<>(lastTurn.payload());
        putIfPresent(payload, "sourceWorkflow", lastTurn.sourceWorkflow());
        putIfPresent(payload, "status", lastTurn.status());
        return payload;
    }

    private Map<String, Object> taskChainPayload(ConversationRuntimeContext.TaskChain chain) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "taskChainId", chain.taskChainId());
        payload.put("schemaVersion", chain.schemaVersion());
        putIfPresent(payload, "status", chain.status());
        if (chain.userGoal() != null) {
            payload.put("userGoal", userGoalPayload(chain.userGoal()));
        }
        List<Map<String, Object>> planTasks = chain.planTasks().stream()
                .map(this::planTaskPayload)
                .toList();
        payload.put("planTasks", planTasks);
        List<Map<String, Object>> steps = chain.steps().stream()
                .map(this::taskStepPayload)
                .toList();
        payload.put("steps", steps);
        putIfPresent(payload, "createdTurnId", chain.createdTurnId());
        putIfPresent(payload, "lastUpdatedTurnId", chain.lastUpdatedTurnId());
        putIfPresent(payload, "createdAt", chain.createdAt());
        putIfPresent(payload, "updatedAt", chain.updatedAt());
        return payload;
    }

    private Map<String, Object> planTaskPayload(ConversationRuntimeContext.PlanTask task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "taskId", task.taskId());
        putIfPresent(payload, "taskName", task.taskName());
        putIfPresent(payload, "taskType", task.taskType());
        putIfPresent(payload, "workflow", task.workflow());
        payload.put("order", task.order());
        putIfPresent(payload, "status", task.status());
        return payload;
    }

    private Map<String, Object> userGoalPayload(ConversationRuntimeContext.UserGoal goal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "goalType", goal.goalType());
        putIfPresent(payload, "goalText", goal.goalText());
        payload.put("isComposite", goal.isComposite());
        putIfPresent(payload, "status", goal.status());
        return payload;
    }

    private Map<String, Object> taskStepPayload(ConversationRuntimeContext.TaskStep step) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stepNo", step.stepNo());
        putIfPresent(payload, "stepId", step.stepId());
        putIfPresent(payload, "taskType", step.taskType());
        putIfPresent(payload, "taskText", step.taskText());
        putIfPresent(payload, "workflow", step.workflow());
        putIfPresent(payload, "status", step.status());
        putIfPresent(payload, "turnId", step.turnId());
        putIfPresent(payload, "startedAt", step.startedAt());
        putIfPresent(payload, "completedAt", step.completedAt());
        payload.put("output", step.output());
        return payload;
    }

    private LocalDateTime parseDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        try {
            return LocalDateTime.parse(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ConversationRuntimeContext.OrderContext copyOrderWithId(
            ConversationRuntimeContext.OrderContext orderContext,
            Long id,
            LocalDateTime expiresAt
    ) {
        return new ConversationRuntimeContext.OrderContext(
                id,
                orderContext.cartSnapshot(),
                orderContext.cartSnapshotHash(),
                orderContext.addressSnapshot(),
                orderContext.amountSnapshot(),
                orderContext.orderStatus(),
                orderContext.failReason(),
                orderContext.orderNo(),
                expiresAt,
                orderContext.payload()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) {
                    map.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return map;
        }
        return new LinkedHashMap<>();
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int intValue(Object value, int fallback) {
        Integer parsed = integerValue(value);
        return parsed == null ? fallback : parsed;
    }

    private BigDecimal decimalValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int rankFromKey(String itemKey) {
        if (!StringUtils.hasText(itemKey) || !itemKey.startsWith("rank:")) {
            return 1;
        }
        try {
            return Integer.parseInt(itemKey.substring("rank:".length()));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private record ContextItemRow(
            Long id,
            Long conversationInternalId,
            String userId,
            String conversationId,
            ConversationContextItemType itemType,
            String itemKey,
            String sourceTurnId,
            String sourceWorkflow,
            ConversationContextItemStatus status,
            String payloadJson,
            LocalDateTime expiresAt
    ) {
    }
}
