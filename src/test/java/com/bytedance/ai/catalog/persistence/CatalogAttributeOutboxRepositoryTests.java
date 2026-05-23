package com.bytedance.ai.catalog.persistence;

import com.bytedance.ai.indexing.api.IndexingCommandFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * catalog_attribute_outbox 仓储集成测试：在 H2 上跑完整的状态机迁移路径，
 * 与 schema-modulith.sql 的 DDL 对齐，覆盖：
 * <ul>
 *   <li>enqueue 首次插入 → 二次复用同一行（不产生重复 PENDING 行）</li>
 *   <li>markSending 抢占语义 + 同一行二次抢占失败</li>
 *   <li>markSent / markFailed / resetForRetry 终态迁移</li>
 *   <li>findDispatchable / findStuckSendingBefore 过滤条件</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-modulith.sql",
        "rag.catalog.enabled=false"
})
class CatalogAttributeOutboxRepositoryTests {

    @Autowired
    private CatalogAttributeOutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private IndexingCommandFacade indexingCommandFacade;

    @BeforeEach
    void cleanTable() {
        jdbcTemplate.update("DELETE FROM catalog_attribute_outbox");
    }

    @Test
    void enqueueInsertsPendingRowOnFirstCall() {
        outboxRepository.enqueue(101L, "SPU-OUT-1", "{\"triggeredBy\":\"import\"}");

        Optional<CatalogAttributeOutboxRecord> latest = outboxRepository.findLatestBySpuId(101L);
        assertThat(latest).isPresent();
        assertThat(latest.get().status()).isEqualTo("PENDING");
        assertThat(latest.get().attemptCount()).isZero();
        assertThat(latest.get().externalRef()).isEqualTo("SPU-OUT-1");
    }

    @Test
    void enqueueReusesExistingRowAndResetsAttemptCount() {
        outboxRepository.enqueue(102L, "SPU-OUT-2", "{}");
        Long firstId = outboxRepository.findLatestBySpuId(102L).orElseThrow().id();
        outboxRepository.markSending(firstId);
        outboxRepository.markFailed(firstId, "boom", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));

        // 二次入队应复用同一行，状态回到 PENDING、attempt_count 清零
        outboxRepository.enqueue(102L, "SPU-OUT-2", "{\"triggeredBy\":\"manual-retry\"}");

        Optional<CatalogAttributeOutboxRecord> reused = outboxRepository.findLatestBySpuId(102L);
        assertThat(reused).isPresent();
        assertThat(reused.get().id()).isEqualTo(firstId);
        assertThat(reused.get().status()).isEqualTo("PENDING");
        assertThat(reused.get().attemptCount()).isZero();
        assertThat(reused.get().lastError()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM catalog_attribute_outbox WHERE spu_id=?", Integer.class, 102L))
                .as("同一 SPU 不应产生重复 outbox 行")
                .isEqualTo(1);
    }

    @Test
    void markSendingIsAtomicAndIdempotent() {
        outboxRepository.enqueue(103L, "SPU-OUT-3", "{}");
        Long id = outboxRepository.findLatestBySpuId(103L).orElseThrow().id();

        assertThat(outboxRepository.markSending(id)).isTrue();
        assertThat(outboxRepository.markSending(id))
                .as("同一行第二次 markSending 应失败，保证多 dispatcher 不重复发")
                .isFalse();
    }

    @Test
    void markSentCompletesTerminalState() {
        outboxRepository.enqueue(104L, "SPU-OUT-4", "{}");
        Long id = outboxRepository.findLatestBySpuId(104L).orElseThrow().id();
        outboxRepository.markSending(id);

        outboxRepository.markSent(id, "MSG-1");

        CatalogAttributeOutboxRecord r = outboxRepository.findLatestBySpuId(104L).orElseThrow();
        assertThat(r.status()).isEqualTo("SENT");
        assertThat(r.messageId()).isEqualTo("MSG-1");
        assertThat(r.lastError()).isNull();
    }

    @Test
    void findDispatchableReturnsPendingOrFailedWithDuePeriod() {
        // 先准备各种状态的行，再以 "查询时刻" 的 now 作为参数，
        // 才能保证 enqueue 写入的 next_send_after (来自数据库 now()) 不晚于查询时刻。
        outboxRepository.enqueue(201L, "SPU-D-1", "{}");                  // PENDING
        outboxRepository.enqueue(202L, "SPU-D-2", "{}");
        Long sendingId = outboxRepository.findLatestBySpuId(202L).orElseThrow().id();
        outboxRepository.markSending(sendingId);
        outboxRepository.markSent(sendingId, "M");                         // SENT

        OffsetDateTime referenceNow = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        outboxRepository.enqueue(203L, "SPU-D-3", "{}");
        Long futureId = outboxRepository.findLatestBySpuId(203L).orElseThrow().id();
        outboxRepository.markSending(futureId);
        outboxRepository.markFailed(futureId, "retry-later", referenceNow.plusMinutes(5));

        outboxRepository.enqueue(204L, "SPU-D-4", "{}");
        Long pastFailId = outboxRepository.findLatestBySpuId(204L).orElseThrow().id();
        outboxRepository.markSending(pastFailId);
        outboxRepository.markFailed(pastFailId, "ready-retry", referenceNow.minusSeconds(1));

        List<CatalogAttributeOutboxRecord> dispatchable = outboxRepository.findDispatchable(referenceNow, 10);
        assertThat(dispatchable)
                .extracting(CatalogAttributeOutboxRecord::spuId)
                .containsExactlyInAnyOrder(201L, 204L);
    }

    @Test
    void findStuckSendingBeforeReturnsStaleSendingRows() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        outboxRepository.enqueue(301L, "SPU-S-1", "{}");
        Long stuckId = outboxRepository.findLatestBySpuId(301L).orElseThrow().id();
        outboxRepository.markSending(stuckId);

        // 把 updated_at 改为远古时间模拟卡住的 SENDING
        jdbcTemplate.update("UPDATE catalog_attribute_outbox SET updated_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf("2020-01-01 00:00:00"), stuckId);

        List<CatalogAttributeOutboxRecord> stuck = outboxRepository.findStuckSendingBefore(now, 10);
        assertThat(stuck).extracting(CatalogAttributeOutboxRecord::id).contains(stuckId);

        outboxRepository.resetForRetry(stuckId, "stuck-reclaim", now);
        assertThat(outboxRepository.findLatestBySpuId(301L).orElseThrow().status()).isEqualTo("FAILED");
    }

    @Test
    void resetForRetryOnlyMovesSendingRows() {
        outboxRepository.enqueue(401L, "SPU-R-1", "{}");
        Long pendingId = outboxRepository.findLatestBySpuId(401L).orElseThrow().id();

        outboxRepository.resetForRetry(pendingId, "noop", OffsetDateTime.now(ZoneOffset.UTC));

        assertThat(outboxRepository.findLatestBySpuId(401L).orElseThrow().status())
                .as("PENDING 行不应被 resetForRetry 修改")
                .isEqualTo("PENDING");
    }
}
