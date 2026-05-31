package com.bytedance.ai.indexing;

import com.bytedance.ai.document.api.DocumentCommandFacade;
import com.bytedance.ai.document.api.DocumentIndexCleanupRequestedEvent;
import com.bytedance.ai.document.api.RagDocumentCreateRequest;
import com.bytedance.ai.document.spi.DocumentIndexingSpi;
import com.bytedance.ai.document.spi.DocumentIndexingView;
import com.bytedance.ai.indexing.api.RagIndexTimelineView;
import com.bytedance.ai.indexing.api.IndexingCommandFacade;
import com.bytedance.ai.indexing.api.IndexingQueryFacade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest(
        module = "indexing",
        mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES,
        extraIncludes = "infrastructure",
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-modulith.sql"
})
class IndexingModuleTests {

    @Autowired
    private DocumentCommandFacade documentCommandFacade;

    @Autowired
    private DocumentIndexingSpi documentIndexingSpi;

    @Autowired
    private IndexingCommandFacade indexingCommandFacade;

    @Autowired
    private IndexingQueryFacade indexingQueryFacade;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void exposesIndexingFacades() {
        assertThat(indexingCommandFacade).isNotNull();
        assertThat(indexingQueryFacade).isNotNull();
    }

    @Test
    void createDocumentTriggersDirectIndexingAndPersistsChunks(Scenario scenario) {
        AtomicReference<Long> documentId = new AtomicReference<>();

        scenario.stimulate(() -> {
                    var documentView = documentCommandFacade.createDocument(new RagDocumentCreateRequest(
                            "markdown",
                            "docs/indexing-flow.md",
                            "indexing-flow-1",
                            "Indexing Flow Test",
                            """
                            # Indexing Flow

                            Spring Modulith helps us keep a modular monolith honest.

                            This test verifies that document creation triggers the indexing pipeline
                            and leaves searchable chunks behind in the indexing module.
                            """,
                            Map.of("tags", java.util.List.of("architecture", "modulith"))
                    ));
                    documentId.set(documentView.id());
                    return documentView;
                })
                .andWaitForStateChange(
                        () -> documentId.get() == null
                                ? null
                                : documentIndexingSpi.findById(documentId.get()).map(DocumentIndexingView::status).orElse(null),
                        "INDEXED"::equals
                )
                .andVerify((status, documentView) -> {
                    assertThat(status).isEqualTo("INDEXED");
                    assertThat(documentIndexingSpi.findById(documentView.id()))
                            .map(DocumentIndexingView::indexedGeneration)
                            .isPresent();
                    Integer activeChunkCount = jdbcTemplate.queryForObject(
                            """
                            SELECT COUNT(*)
                              FROM rag_chunks c
                              JOIN rag_documents d ON d.id = c.document_id
                             WHERE c.document_id = ?
                               AND d.indexed_generation = c.index_generation
                            """,
                            Integer.class,
                            documentView.id()
                    );
                    assertThat(activeChunkCount).isPositive();
                });
    }

    @Test
    void deleteDocumentTriggersDirectCleanupAndRemovesDocument(Scenario scenario) {
        Long documentId = documentCommandFacade.createDocument(new RagDocumentCreateRequest(
                "markdown",
                "docs/direct-delete.md",
                "direct-delete-1",
                "Direct Delete Test",
                "# Delete\n\nThis document verifies direct-mode cleanup.",
                Map.of()
        )).id();

        scenario.stimulate(() -> {
                    documentCommandFacade.deleteDocument(documentId);
                    return documentId;
                })
                .andWaitForEventOfType(DocumentIndexCleanupRequestedEvent.class)
                .matchingMappedValue(DocumentIndexCleanupRequestedEvent::documentId, documentId)
                .toArrive();

        await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(documentIndexingSpi.findById(documentId)).isEmpty());
    }

    @Test
    void directDeleteAlsoRemovesTransitionAuditRows(Scenario scenario) {
        Long documentId = documentCommandFacade.createDocument(new RagDocumentCreateRequest(
                "markdown",
                "docs/direct-delete-transitions.md",
                "direct-delete-transitions-1",
                "Direct Delete Transition Cleanup Test",
                "# Delete\n\nThis document verifies transition cleanup.",
                Map.of()
        )).id();

        jdbcTemplate.update(
                """
                INSERT INTO rag_index_job_transitions (
                    document_id, job_id, outbox_id, content_sha256, from_state, to_state, event,
                    trigger_type, triggered_by, success, failure_reason, error_message, message_id, metadata
                ) VALUES (?, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                documentId,
                "sha-transition-test",
                "QUEUED",
                "FAILED",
                "FAIL",
                "TEST",
                "indexing-test",
                false,
                "manual",
                "transition should be deleted with document",
                null,
                "{}"
        );

        scenario.stimulate(() -> {
                    documentCommandFacade.deleteDocument(documentId);
                    return documentId;
                })
                .andWaitForEventOfType(DocumentIndexCleanupRequestedEvent.class)
                .matchingMappedValue(DocumentIndexCleanupRequestedEvent::documentId, documentId)
                .toArrive();

        await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    assertThat(documentIndexingSpi.findById(documentId)).isEmpty();
                    Integer transitionCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM rag_index_job_transitions WHERE document_id = ?",
                            Integer.class,
                            documentId
                    );
                    assertThat(transitionCount).isZero();
                });
    }

    @Test
    void indexTimelineExposesOutboxMessageId() {
        jdbcTemplate.update(
                """
                INSERT INTO rag_documents (
                    source_type, source_uri, external_ref, title, content, content_sha256,
                    indexed_generation, status, chunk_count, attempt_count, metadata, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                "markdown",
                "docs/timeline-outbox.md",
                "timeline-outbox-1",
                "Timeline Outbox MessageId Test",
                "# Timeline",
                "sha-timeline-1",
                null,
                "FAILED",
                0,
                0,
                "{}"
        );
        Long documentId = jdbcTemplate.queryForObject(
                "SELECT id FROM rag_documents WHERE content_sha256 = ?",
                Long.class,
                "sha-timeline-1"
        );
        jdbcTemplate.update(
                """
                INSERT INTO rag_index_outbox (
                    document_id, content_sha256, event_type, status, attempt_count,
                    message_id, last_error, next_attempt_at, dispatched_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                documentId,
                "sha-timeline-1",
                "INDEX_DOCUMENT",
                "SENT",
                1,
                "producer-message-1",
                null
        );

        RagIndexTimelineView timeline = indexingQueryFacade.getIndexTimeline(documentId);

        assertThat(timeline.outbox()).isNotNull();
        assertThat(timeline.outbox().messageId()).isEqualTo("producer-message-1");
    }
}
