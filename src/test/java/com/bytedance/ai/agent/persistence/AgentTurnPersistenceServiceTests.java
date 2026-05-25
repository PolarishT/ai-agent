package com.bytedance.ai.agent.persistence;

import com.bytedance.ai.agent.persistence.jdbc.JdbcAgentTurnRepository;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTurnPersistenceServiceTests {

    private AgentTurnPersistenceService persistenceService;

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        DataSource dataSource = dataSource();
        new ResourceDatabasePopulator(new ClassPathResource("schema-modulith.sql")).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        persistenceService = new AgentTurnPersistenceService(
                new JdbcAgentTurnRepository(jdbcTemplate),
                new RagJsonCodec(JsonMapper.builder().build())
        );
        jdbcTemplate.update("DELETE FROM agent_turn");
    }

    private DataSource dataSource() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:agentturn-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "sa",
                "",
                true
        );
        dataSource.setDriverClassName("org.h2.Driver");
        return dataSource;
    }

    @Test
    void createRunningPersistsIdempotencyFields() {
        AgentTurnRecord record = persistenceService.createRunning(
                "turn-1",
                "corr-1",
                "u1",
                "c1",
                "req-1",
                "推荐 300 元以下的双肩包"
        );

        assertThat(record.status()).isEqualTo("RUNNING");
        assertThat(record.turnId()).isEqualTo("turn-1");
        assertThat(record.requestId()).isEqualTo("req-1");
        assertThat(persistenceService.findByRequestId("u1", "c1", "req-1"))
                .map(AgentTurnRecord::correlationId)
                .contains("corr-1");
    }

    @Test
    void recordsConversationIntentToolStateAndSuccess() {
        persistenceService.createRunning("turn-2", "corr-2", "u1", "c1", null, "有没有轻便电脑包");

        persistenceService.attachConversationMessages("turn-2", "101", "102");
        persistenceService.recordIntent(
                "turn-2",
                "RECOMMEND_VAGUE",
                "rule_l2",
                0.85,
                Map.of("must", List.of("轻便"), "categoryHint", "电脑包")
        );
        persistenceService.recordToolState(
                "turn-2",
                List.of(Map.of("toolName", "search_products")),
                List.of(Map.of("spuId", 99L, "title", "轻便电脑包"))
        );
        persistenceService.markSucceeded(
                "turn-2",
                "推荐 [#1]",
                true,
                12,
                24,
                321,
                "用户偏好轻便电脑包",
                6,
                "agent-memory-summary-v1"
        );

        AgentTurnRecord record = persistenceService.findByTurnId("turn-2").orElseThrow();
        assertThat(record.userMessageId()).isEqualTo("101");
        assertThat(record.assistantMessageId()).isEqualTo("102");
        assertThat(record.intent()).isEqualTo("RECOMMEND_VAGUE");
        assertThat(record.intentSource()).isEqualTo("rule_l2");
        assertThat(record.intentConfidence()).isEqualTo(0.85);
        assertThat(record.slotsJson()).contains("轻便");
        assertThat(record.toolsCalled()).contains("search_products");
        assertThat(record.cardsEmitted()).contains("轻便电脑包");
        assertThat(record.status()).isEqualTo("SUCCEEDED");
        assertThat(record.answerText()).isEqualTo("推荐 [#1]");
        assertThat(record.generatedByModel()).isTrue();
        assertThat(record.tokensIn()).isEqualTo(12);
        assertThat(record.tokensOut()).isEqualTo(24);
        assertThat(record.latencyMs()).isEqualTo(321);
        assertThat(record.memorySummary()).isEqualTo("用户偏好轻便电脑包");
        assertThat(record.memorySummaryMessageCount()).isEqualTo(6);
        assertThat(record.memorySummaryModel()).isEqualTo("agent-memory-summary-v1");
        assertThat(persistenceService.findLatestMemorySummary("c1"))
                .map(AgentTurnRecord::turnId)
                .contains("turn-2");
        assertThat(record.completedAt()).isNotNull();
    }

    @Test
    void markFailedOnlyMovesRunningTurn() {
        persistenceService.createRunning("turn-3", "corr-3", "u1", "c1", null, "写段代码");

        persistenceService.markFailed("turn-3", "OUT_OF_SCOPE", "暂不支持该请求", 45);
        persistenceService.markSucceeded("turn-3", "should-not-win", true, null, null, 50);

        AgentTurnRecord record = persistenceService.findByTurnId("turn-3").orElseThrow();
        assertThat(record.status()).isEqualTo("FAILED");
        assertThat(record.errorCode()).isEqualTo("OUT_OF_SCOPE");
        assertThat(record.errorMessage()).isEqualTo("暂不支持该请求");
        assertThat(record.answerText()).isNull();
    }

    @Test
    void recordIntentAcceptsWorkflowSourceAndRejectsUnknown() {
        persistenceService.createRunning("turn-wf", "corr-wf", "u1", "c-wf", null, "推荐洗面奶");

        // workflow turn 走 EcommerceWorkflowEngine 后 intentSource = "workflow"，必须能持久化。
        persistenceService.recordIntent(
                "turn-wf",
                "RECOMMEND_VAGUE",
                "workflow",
                null,
                Map.of("categoryHint", "洗面奶")
        );
        assertThat(persistenceService.findByTurnId("turn-wf").orElseThrow().intentSource())
                .isEqualTo("workflow");

        // 任何不在白名单内的 source 仍必须被 DB CHECK 拒绝，防止退化为空字符串等坏值。
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                persistenceService.recordIntent(
                        "turn-wf",
                        "RECOMMEND_VAGUE",
                        "magic_unknown",
                        null,
                        Map.of()
                )
        ).hasMessageContaining("intent_source");
    }

    @Test
    void findRecentByConversationReturnsNewestFirst() {
        persistenceService.createRunning("turn-4a", "corr-4a", "u1", "c-recent", null, "第一条");
        persistenceService.createRunning("turn-4b", "corr-4b", "u1", "c-recent", null, "第二条");

        List<AgentTurnRecord> records = persistenceService.findRecentByConversationId("c-recent", 10);

        assertThat(records).extracting(AgentTurnRecord::turnId).containsExactly("turn-4b", "turn-4a");
    }
}
