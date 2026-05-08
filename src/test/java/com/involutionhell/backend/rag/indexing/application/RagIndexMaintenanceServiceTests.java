package com.involutionhell.backend.rag.indexing.application;

import com.involutionhell.backend.rag.indexing.model.RagIndexJobStatus;
import com.involutionhell.backend.rag.indexing.model.RagIndexStage;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRecord;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRepository;
import com.involutionhell.backend.rag.indexing.persistence.jdbcImpl.JdbcRagIndexJobRepository;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowCommand;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowService;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RagIndexMaintenanceServiceTests {

    @Test
    void skipsTerminalJobsWithoutFailingWorkflowOrCleaningGeneration() {
        TestJobRepository jobRepository = new TestJobRepository(List.of(
                job(1L, RagIndexJobStatus.FAILED, RagIndexStage.COMMIT_INDEX, 101L),
                job(2L, RagIndexJobStatus.SUCCEEDED, RagIndexStage.COMPLETED, 102L),
                job(3L, RagIndexJobStatus.SKIPPED, RagIndexStage.SKIPPED, 103L)
        ));
        RecordingIndexingService indexingService = new RecordingIndexingService();
        RecordingWorkflowService workflowService = new RecordingWorkflowService();
        RagIndexMaintenanceService service = newService(jobRepository, indexingService, workflowService);

        int repaired = service.executeOrphanCleanup();

        assertThat(repaired).isZero();
        assertThat(workflowService.failCommands).isEmpty();
        assertThat(indexingService.deletedGenerations).isEmpty();
    }

    @Test
    void marksNonTerminalStaleJobWithoutTargetGenerationAsFailed() {
        TestJobRepository jobRepository = new TestJobRepository(List.of(
                job(1L, RagIndexJobStatus.RUNNING, RagIndexStage.VECTOR_INDEXING, null)
        ));
        RecordingIndexingService indexingService = new RecordingIndexingService();
        RecordingWorkflowService workflowService = new RecordingWorkflowService();
        RagIndexMaintenanceService service = newService(jobRepository, indexingService, workflowService);

        int repaired = service.executeOrphanCleanup();

        assertThat(repaired).isEqualTo(1);
        assertThat(workflowService.failCommands).hasSize(1);
        assertThat(indexingService.deletedGenerations).isEmpty();
    }

    @Test
    void cleansGenerationThenFailsNonTerminalStaleJobWithTargetGeneration() {
        TestJobRepository jobRepository = new TestJobRepository(List.of(
                job(1L, RagIndexJobStatus.RUNNING, RagIndexStage.VECTOR_INDEXING, 777L)
        ));
        RecordingIndexingService indexingService = new RecordingIndexingService();
        RecordingWorkflowService workflowService = new RecordingWorkflowService();
        RagIndexMaintenanceService service = newService(jobRepository, indexingService, workflowService);

        int repaired = service.executeOrphanCleanup();

        assertThat(repaired).isEqualTo(1);
        assertThat(indexingService.deletedGenerations).containsExactly(new DeletedGeneration(1L, 777L));
        assertThat(workflowService.failCommands).hasSize(1);
    }

    @Test
    void jdbcFindStaleJobsDoesNotReturnTerminalStatuses() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        createJobTable(jdbc);
        OffsetDateTime oldTime = OffsetDateTime.now().minusHours(2);
        insertJob(jdbc, 1L, RagIndexJobStatus.RUNNING, RagIndexStage.VECTOR_INDEXING, oldTime);
        insertJob(jdbc, 2L, RagIndexJobStatus.QUEUED, RagIndexStage.QUEUED, oldTime);
        insertJob(jdbc, 3L, RagIndexJobStatus.FAILED, RagIndexStage.COMMIT_INDEX, oldTime);
        insertJob(jdbc, 4L, RagIndexJobStatus.SUCCEEDED, RagIndexStage.COMPLETED, oldTime);
        insertJob(jdbc, 5L, RagIndexJobStatus.SKIPPED, RagIndexStage.SKIPPED, oldTime);

        JdbcRagIndexJobRepository repository = new JdbcRagIndexJobRepository(jdbc);

        List<RagIndexJobRecord> staleJobs = repository.findStaleJobs(OffsetDateTime.now().minusHours(1), 10);

        assertThat(staleJobs)
                .extracting(RagIndexJobRecord::status)
                .containsExactly(RagIndexJobStatus.RUNNING.name(), RagIndexJobStatus.QUEUED.name());
    }

    private static RagIndexMaintenanceService newService(
            RagIndexJobRepository jobRepository,
            RagIndexingService indexingService,
            IndexWorkflowService workflowService
    ) {
        return new RagIndexMaintenanceService(
                RagProperties.defaults(),
                jobRepository,
                indexingService,
                workflowService,
                transactionTemplate()
        );
    }

    private static TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        });
    }

    private static RagIndexJobRecord job(
            Long documentId,
            RagIndexJobStatus status,
            RagIndexStage stage,
            Long targetGeneration
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        return new RagIndexJobRecord(
                documentId,
                documentId,
                "sha-" + documentId,
                status.name(),
                stage.name(),
                0L,
                null,
                1,
                targetGeneration,
                null,
                null,
                now.minusMinutes(30),
                null,
                now.minusHours(2),
                now.minusHours(2)
        );
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:rag_index_maintenance;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    private static void createJobTable(JdbcTemplate jdbc) {
        jdbc.execute(
                """
                CREATE TABLE rag_index_jobs (
                    id BIGINT PRIMARY KEY,
                    document_id BIGINT NOT NULL,
                    content_sha256 VARCHAR(64) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    stage VARCHAR(32) NOT NULL,
                    version BIGINT NOT NULL DEFAULT 0,
                    last_event VARCHAR(32),
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    target_generation BIGINT,
                    message_id VARCHAR(128),
                    last_error TEXT,
                    started_at TIMESTAMP,
                    finished_at TIMESTAMP,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """
        );
    }

    private static void insertJob(
            JdbcTemplate jdbc,
            Long id,
            RagIndexJobStatus status,
            RagIndexStage stage,
            OffsetDateTime updatedAt
    ) {
        jdbc.update(
                """
                INSERT INTO rag_index_jobs (
                    id, document_id, content_sha256, status, stage, version, attempt_count, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?)
                """,
                id,
                id,
                "sha-" + id,
                status.name(),
                stage.name(),
                Timestamp.from(updatedAt.minusHours(1).toInstant()),
                Timestamp.from(updatedAt.toInstant())
        );
    }

    private static final class TestJobRepository implements RagIndexJobRepository {

        private final List<RagIndexJobRecord> staleJobs;

        private TestJobRepository(List<RagIndexJobRecord> staleJobs) {
            this.staleJobs = staleJobs;
        }

        @Override
        public void queue(Long documentId, String contentSha256) {
        }

        @Override
        public void attachMessageId(Long documentId, String contentSha256, String messageId) {
        }

        @Override
        public void annotateEvent(Long documentId, String contentSha256, String event) {
        }

        @Override
        public int startAttempt(Long documentId, String contentSha256, RagIndexStage fromStage, Long targetGeneration) {
            return 0;
        }

        @Override
        public int updateStage(Long documentId, String contentSha256, RagIndexStage fromStage, RagIndexStage toStage) {
            return 0;
        }

        @Override
        public void recordRetry(Long documentId, String contentSha256, RagIndexStage stage, String errorMessage) {
        }

        @Override
        public int markSucceeded(Long documentId, String contentSha256, RagIndexStage fromStage, Long targetGeneration) {
            return 0;
        }

        @Override
        public int markFailed(Long documentId, String contentSha256, RagIndexStage fromStage, RagIndexStage failureStage, String errorMessage) {
            return 0;
        }

        @Override
        public int markSkipped(Long documentId, String contentSha256, RagIndexStage fromStage, String reason) {
            return 0;
        }

        @Override
        public Optional<RagIndexJobRecord> findByDocumentIdAndContentSha256(Long documentId, String contentSha256) {
            return Optional.empty();
        }

        @Override
        public void deleteByDocumentId(Long documentId) {
        }

        @Override
        public List<RagIndexJobRecord> findStaleJobs(OffsetDateTime updatedBefore, int limit) {
            return new ArrayList<>(staleJobs);
        }
    }

    private static final class RecordingIndexingService extends RagIndexingService {

        private final List<DeletedGeneration> deletedGenerations = new ArrayList<>();

        private RecordingIndexingService() {
            super(null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public void deleteOrphanedIndexingState(Long documentId, long generation) {
            deletedGenerations.add(new DeletedGeneration(documentId, generation));
        }
    }

    private static final class RecordingWorkflowService extends IndexWorkflowService {

        private final List<IndexWorkflowCommand> failCommands = new ArrayList<>();

        private RecordingWorkflowService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public void fail(IndexWorkflowCommand command) {
            failCommands.add(command);
        }
    }

    private record DeletedGeneration(Long documentId, long generation) {
    }
}
