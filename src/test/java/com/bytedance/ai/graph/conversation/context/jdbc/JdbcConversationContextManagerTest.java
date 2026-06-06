package com.bytedance.ai.graph.conversation.context.jdbc;

import com.bytedance.ai.graph.conversation.context.ConversationContextItemStatus;
import com.bytedance.ai.graph.conversation.context.ConversationRuntimeContext;
import com.bytedance.ai.graph.conversation.persistence.jdbc.JdbcAgentConversationRepository;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcConversationContextManagerTest {

    private JdbcTemplate jdbc;
    private JdbcConversationContextManager manager;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:conversation-context-" + java.util.UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "sa",
                ""
        ));
        createSchema();
        manager = new JdbcConversationContextManager(
                new NamedParameterJdbcTemplate(jdbc),
                new JdbcAgentConversationRepository(jdbc),
                new RagJsonCodec(JsonMapper.builder().build())
        );
    }

    @Test
    void savesLoadsSupersedesConsumesAndExpiresContextItems() {
        LocalDateTime later = LocalDateTime.now().plusMinutes(30);

        manager.saveProductCandidates(
                "user-1",
                "conversation-1",
                "turn-1",
                "product_query_workflow",
                List.of(candidate(1, "101", "SKU-1", "元气森林 白葡萄味苏打气泡水")),
                later
        );
        manager.updateFocus(
                "user-1",
                "conversation-1",
                "turn-1",
                "product_query_workflow",
                new ConversationRuntimeContext.Focus(
                        null,
                        "current",
                        1,
                        "101",
                        "SKU-1",
                        "元气森林 白葡萄味苏打气泡水",
                        Map.of("rank", 1)
                ),
                later
        );
        manager.updateLastTurn(
                "user-1",
                "conversation-1",
                "turn-1",
                "product_query_workflow",
                new ConversationRuntimeContext.LastTurn(
                        null,
                        "product_query_workflow",
                        "FOUND",
                        Map.of("condition", Map.of("flavor", "白葡萄"))
                ),
                later
        );

        ConversationRuntimeContext loaded = manager.load("user-1", "conversation-1");
        assertThat(loaded.productCandidates()).extracting(ConversationRuntimeContext.ProductCandidateItem::productId)
                .containsExactly("101");
        assertThat(loaded.focus().productId()).isEqualTo("101");
        assertThat(loaded.lastResult("product_query_workflow").status()).isEqualTo("FOUND");

        manager.saveProductCandidates(
                "user-1",
                "conversation-1",
                "turn-2",
                "product_query_workflow",
                List.of(candidate(1, "202", "SKU-2", "农夫山泉 茶π 白葡萄味")),
                later
        );
        assertThat(count("PRODUCT_CANDIDATE", "ACTIVE")).isEqualTo(1);
        assertThat(count("PRODUCT_CANDIDATE", "SUPERSEDED")).isEqualTo(1);
        assertThat(manager.load("user-1", "conversation-1").productCandidates())
                .extracting(ConversationRuntimeContext.ProductCandidateItem::productId)
                .containsExactly("202");

        ConversationRuntimeContext.PendingClarification clarification = manager.savePendingClarification(
                "user-1",
                "conversation-1",
                "turn-3",
                "cart_manage_workflow",
                new ConversationRuntimeContext.PendingClarification(
                        null,
                        "CART_CANDIDATE_SELECTION",
                        "cart_manage_workflow",
                        1,
                        List.of(candidate(1, "202", "SKU-2", "农夫山泉 茶π 白葡萄味")),
                        later,
                        Map.of("source", "test")
                ),
                later
        );
        assertThat(manager.load("user-1", "conversation-1").pendingClarification()).isNotNull();

        manager.consumePendingClarification(clarification.contextItemId());
        assertThat(manager.load("user-1", "conversation-1").pendingClarification()).isNull();
        assertThat(count("PENDING_CLARIFICATION", "CONSUMED")).isEqualTo(1);

        manager.savePendingClarification(
                "user-1",
                "conversation-1",
                "turn-4",
                "cart_manage_workflow",
                new ConversationRuntimeContext.PendingClarification(
                        null,
                        "CART_CANDIDATE_SELECTION",
                        "cart_manage_workflow",
                        1,
                        List.of(candidate(1, "303", "SKU-3", "过期候选")),
                        LocalDateTime.now().minusSeconds(1),
                        Map.of()
                ),
                LocalDateTime.now().minusSeconds(1)
        );
        assertThat(manager.load("user-1", "conversation-1").pendingClarification()).isNull();
        assertThat(count("PENDING_CLARIFICATION", "EXPIRED")).isEqualTo(1);
    }

    @Test
    void taskChainRoundTripCoversSaveLoadMarkPlanTaskAndTransitionStatus() {
        LocalDateTime now = LocalDateTime.now();
        ConversationRuntimeContext.UserGoal goal = new ConversationRuntimeContext.UserGoal(
                "PRODUCT_DISCOVERY", "买杯水", true, "RUNNING"
        );
        ConversationRuntimeContext.PlanTask task1 = new ConversationRuntimeContext.PlanTask(
                "task-1", "搜商品", "PRODUCT_SEARCH", "product_query_workflow", 1, "PENDING");
        ConversationRuntimeContext.TaskChain chain = new ConversationRuntimeContext.TaskChain(
                null, "chain-1", 1, "PLANNING",
                goal, List.of(task1), List.of(), "turn-1", "turn-1", now, now
        );

        // save + load：planTasks 落库回来
        manager.saveTaskChain("u1", "c1", "turn-1", "main_intent_router", chain, null);
        ConversationRuntimeContext.TaskChain loaded = manager.loadTaskChain("u1", "c1", "chain-1");
        assertThat(loaded).isNotNull();
        assertThat(loaded.taskChainId()).isEqualTo("chain-1");
        assertThat(loaded.userGoal().isComposite()).isTrue();
        assertThat(loaded.planTasks()).hasSize(1);
        assertThat(loaded.planTasks().get(0).workflow()).isEqualTo("product_query_workflow");
        assertThat(loaded.steps()).isEmpty();

        // markPlanTask：SUCCEEDED + 追加 step（含 output map）
        ConversationRuntimeContext.TaskStep executed = new ConversationRuntimeContext.TaskStep(
                1, "step-1", "PRODUCT_SEARCH", "搜", "product_query_workflow", "SUCCEEDED",
                "turn-2", now, now, Map.of("candidateCount", 3));
        assertThat(manager.markPlanTask("u1", "c1", "chain-1", "task-1", "SUCCEEDED", executed, "turn-2")).isTrue();
        loaded = manager.loadTaskChain("u1", "c1", "chain-1");
        assertThat(loaded.planTasks().get(0).status()).isEqualTo("SUCCEEDED");
        assertThat(loaded.steps()).hasSize(1);
        assertThat(loaded.steps().get(0).output()).containsEntry("candidateCount", 3);
        assertThat(loaded.lastUpdatedTurnId()).isEqualTo("turn-2");
        assertThat(loaded.nextPendingTask()).isNull();

        // chain 整体推到 SUCCEEDED
        assertThat(manager.transitionChainStatus("u1", "c1", "chain-1", "SUCCEEDED", "turn-3")).isTrue();
        loaded = manager.loadTaskChain("u1", "c1", "chain-1");
        assertThat(loaded.status()).isEqualTo("SUCCEEDED");
        assertThat(loaded.lastUpdatedTurnId()).isEqualTo("turn-3");

        // 不存在的 task / chain 返回 false
        assertThat(manager.markPlanTask("u1", "c1", "chain-1", "no-task", "SUCCEEDED", null, null)).isFalse();
        assertThat(manager.markPlanTask("u1", "c1", "no-such-chain", "task-1", "SUCCEEDED", null, null)).isFalse();
        assertThat(manager.transitionChainStatus("u1", "c1", "no-such-chain", "FAILED", null)).isFalse();

        // supersede 链路：最新一条 ACTIVE，前几版 SUPERSEDED
        assertThat(count("TASK_CHAIN", "ACTIVE")).isEqualTo(1);
        assertThat(count("TASK_CHAIN", "SUPERSEDED")).isEqualTo(2);

        // load() 主入口也能拿到 chain
        ConversationRuntimeContext context = manager.load("u1", "c1");
        assertThat(context.taskChains()).hasSize(1);
        assertThat(context.taskChains().get(0).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void expiredTaskChainIsAutoMarkedExpiredOnNextLoadAndHiddenFromContext() {
        // 1. 写一条 EXECUTING chain，expires_at 在过去（即"上次活动是 30 秒前，配置 timeout 已超"）
        LocalDateTime expired = LocalDateTime.now().minusSeconds(30);
        ConversationRuntimeContext.TaskStep step = new ConversationRuntimeContext.TaskStep(
                1, "step-1", "PRODUCT_SEARCH", "搜",
                "product_query_workflow", "SUCCEEDED",
                "turn-1", LocalDateTime.now().minusHours(2),
                LocalDateTime.now().minusHours(2), Map.of()
        );
        ConversationRuntimeContext.TaskChain chain = new ConversationRuntimeContext.TaskChain(
                null, "chain-stale", 1, "EXECUTING",
                new ConversationRuntimeContext.UserGoal("PRODUCT_SEARCH", "找", false, "RUNNING"),
                List.of(), List.of(step), "turn-1", "turn-1",
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(2)
        );
        manager.saveTaskChain("u1", "c1", "turn-1", "product_query_workflow", chain, expired);

        // 验证落库时是 ACTIVE
        assertThat(count("TASK_CHAIN", "ACTIVE")).isEqualTo(1);

        // 2. 下一轮 load() —— 会触发 expireStaleItems
        ConversationRuntimeContext loaded = manager.load("u1", "c1");

        // 3. 主入口拿不到这条 chain
        assertThat(loaded.taskChains()).isEmpty();
        // 4. loadTaskChain 单独按 id 查也拿不到
        assertThat(manager.loadTaskChain("u1", "c1", "chain-stale")).isNull();
        // 5. DB 行已经被标 EXPIRED
        assertThat(count("TASK_CHAIN", "EXPIRED")).isEqualTo(1);
        assertThat(count("TASK_CHAIN", "ACTIVE")).isEqualTo(0);
    }

    @Test
    void taskChainWithFutureExpiresAtStaysActive() {
        // EXECUTING chain，expires_at 在未来 → load 不应该清掉
        LocalDateTime future = LocalDateTime.now().plusMinutes(30);
        ConversationRuntimeContext.TaskChain chain = new ConversationRuntimeContext.TaskChain(
                null, "chain-fresh", 1, "EXECUTING",
                null, List.of(), List.of(), "turn-1", "turn-1",
                LocalDateTime.now(), LocalDateTime.now()
        );
        manager.saveTaskChain("u1", "c1", "turn-1", "wf", chain, future);

        ConversationRuntimeContext loaded = manager.load("u1", "c1");

        assertThat(loaded.taskChains()).hasSize(1);
        assertThat(loaded.taskChains().get(0).taskChainId()).isEqualTo("chain-fresh");
        assertThat(count("TASK_CHAIN", "ACTIVE")).isEqualTo(1);
        assertThat(count("TASK_CHAIN", "EXPIRED")).isEqualTo(0);
    }

    @Test
    void concurrentMarkPlanTaskMergesBothUpdatesWithoutLoss() throws Exception {
        // 两个独立 PENDING 任务的链：模拟同一会话两个并发 turn 各推进一个任务。
        LocalDateTime now = LocalDateTime.now();
        ConversationRuntimeContext.PlanTask task1 = new ConversationRuntimeContext.PlanTask(
                "task-1", "搜商品", "PRODUCT_SEARCH", "product_query_workflow", 1, "PENDING");
        ConversationRuntimeContext.PlanTask task2 = new ConversationRuntimeContext.PlanTask(
                "task-2", "加购物车", "ADD_TO_CART", "cart_manage_workflow", 2, "PENDING");
        ConversationRuntimeContext.TaskChain chain = new ConversationRuntimeContext.TaskChain(
                null, "chain-1", 1, "EXECUTING",
                new ConversationRuntimeContext.UserGoal("PRODUCT_DISCOVERY", "买杯水", true, "RUNNING"),
                List.of(task1, task2), List.of(), "turn-0", "turn-0", now, now
        );
        manager.saveTaskChain("u1", "c1", "turn-0", "main_intent_router", chain, null);

        // 子类编排出确定性竞争：两个 turn 都以同一旧活跃行（row#1）为基准（首读卡栅栏），
        // 但让先到者（A）完整写完 row#2 后，后到者（B）才带着陈旧基准去写——B 的 supersedeById 必
        // 然命中 0 行（CAS 落败）→ 重新加载 row#2 再应用。单测无 Spring 事务、按语句自动提交，
        // 这样排序可避开 supersede 与 insert 之间的非原子空窗，同时仍真实触发"基于旧 chain 更新"。
        AtomicBoolean firstComer = new AtomicBoolean(true);
        CountDownLatch laterCapturedStaleBase = new CountDownLatch(1);
        CountDownLatch earlierFinishedWrite = new CountDownLatch(1);
        JdbcConversationContextManager racing = new JdbcConversationContextManager(
                new NamedParameterJdbcTemplate(jdbc),
                new JdbcAgentConversationRepository(jdbc),
                new RagJsonCodec(JsonMapper.builder().build())
        ) {
            final ThreadLocal<Boolean> firstLoad = ThreadLocal.withInitial(() -> Boolean.TRUE);

            @Override
            public ConversationRuntimeContext.TaskChain loadTaskChain(String u, String c, String id) {
                ConversationRuntimeContext.TaskChain loaded = super.loadTaskChain(u, c, id);
                if (!Boolean.TRUE.equals(firstLoad.get())) {
                    return loaded; // 重试读直接放行
                }
                firstLoad.set(Boolean.FALSE);
                try {
                    if (firstComer.getAndSet(false)) {
                        // A：先到者，等 B 也读到 row#1（陈旧基准就位）后再返回去写
                        laterCapturedStaleBase.await(5, TimeUnit.SECONDS);
                    } else {
                        // B：后到者，宣告已读到 row#1，再等 A 把 row#2 整段写完
                        laterCapturedStaleBase.countDown();
                        earlierFinishedWrite.await(5, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return loaded;
            }
        };

        ConversationRuntimeContext.TaskStep step1 = new ConversationRuntimeContext.TaskStep(
                1, "step-1", "PRODUCT_SEARCH", "搜", "product_query_workflow", "SUCCEEDED",
                "turn-1", now, now, Map.of("candidateCount", 3));
        ConversationRuntimeContext.TaskStep step2 = new ConversationRuntimeContext.TaskStep(
                1, "step-2", "ADD_TO_CART", "加购", "cart_manage_workflow", "SUCCEEDED",
                "turn-2", now, now, Map.of("quantity", 1));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> f1 = pool.submit(() -> {
                try {
                    return racing.markPlanTask("u1", "c1", "chain-1", "task-1", "SUCCEEDED", step1, "turn-1");
                } finally {
                    earlierFinishedWrite.countDown(); // A 写完整段后放行 B；B 调用为幂等空操作
                }
            });
            Future<Boolean> f2 = pool.submit(() -> {
                try {
                    return racing.markPlanTask("u1", "c1", "chain-1", "task-2", "SUCCEEDED", step2, "turn-2");
                } finally {
                    earlierFinishedWrite.countDown();
                }
            });
            assertThat(f1.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(f2.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // 没有丢更新：两个任务都 SUCCEEDED、两条 step 都在，且只有一行 ACTIVE。
        ConversationRuntimeContext.TaskChain merged = manager.loadTaskChain("u1", "c1", "chain-1");
        assertThat(merged).isNotNull();
        assertThat(merged.planTasks()).extracting(ConversationRuntimeContext.PlanTask::status)
                .containsOnly("SUCCEEDED");
        assertThat(merged.steps()).extracting(ConversationRuntimeContext.TaskStep::stepId)
                .containsExactlyInAnyOrder("step-1", "step-2");
        assertThat(count("TASK_CHAIN", "ACTIVE")).isEqualTo(1);
    }

    private ConversationRuntimeContext.ProductCandidateItem candidate(
            int rank,
            String productId,
            String skuId,
            String productName
    ) {
        return new ConversationRuntimeContext.ProductCandidateItem(
                null,
                rank,
                productId,
                skuId,
                productName,
                new BigDecimal("9.90"),
                "brief",
                "spec",
                "external-ref",
                10,
                Map.of("rank", rank)
        );
    }

    private int count(String itemType, String status) {
        Integer count = jdbc.queryForObject(
                """
                        SELECT COUNT(*)
                          FROM public.agent_context_items
                         WHERE item_type = ?
                           AND status = ?
                        """,
                Integer.class,
                itemType,
                status
        );
        return count == null ? 0 : count;
    }

    private void createSchema() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS public");
        jdbc.execute("""
                CREATE TABLE public.agent_conversations (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    conversation_id VARCHAR(128) NOT NULL UNIQUE,
                    user_id VARCHAR(128) NOT NULL,
                    title VARCHAR(200),
                    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    message_count INTEGER NOT NULL DEFAULT 0,
                    next_turn_seq BIGINT NOT NULL DEFAULT 0,
                    metadata CLOB NOT NULL DEFAULT '{}',
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_message_at TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE public.agent_conversation_messages (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    message_id VARCHAR(128) NOT NULL UNIQUE,
                    conversation_id BIGINT NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    content CLOB NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'SUCCEEDED',
                    token_count INTEGER,
                    correlation_id VARCHAR(128),
                    sequence_no INTEGER NOT NULL,
                    metadata CLOB NOT NULL DEFAULT '{}',
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (conversation_id, sequence_no)
                )
                """);
        jdbc.execute("""
                CREATE TABLE public.agent_context_items (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    conversation_internal_id BIGINT NOT NULL,
                    user_id VARCHAR(128) NOT NULL,
                    conversation_id VARCHAR(128) NOT NULL,
                    item_type VARCHAR(64) NOT NULL,
                    item_key VARCHAR(128) NOT NULL,
                    source_turn_id VARCHAR(128),
                    source_workflow VARCHAR(128),
                    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                    payload_json jsonb NOT NULL DEFAULT '{}',
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    expires_at TIMESTAMP
                )
                """);
    }
}
