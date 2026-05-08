package com.involutionhell.backend.rag.indexing.workflow;

import com.involutionhell.backend.rag.document.spi.DocumentIndexingSpi;
import com.involutionhell.backend.rag.document.spi.DocumentIndexingView;
import com.involutionhell.backend.rag.indexing.model.RagIndexStage;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRecord;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IndexWorkflowProjectorTests {

    @Test
    void requeuesFailedStateWithoutParsingFailedAsStage() {
        RecordingDocumentIndexingSpi documentIndexingSpi = new RecordingDocumentIndexingSpi();
        RecordingJobRepository jobRepository = new RecordingJobRepository();
        IndexWorkflowProjector projector = new IndexWorkflowProjector(documentIndexingSpi, jobRepository);
        IndexWorkflowCommand command = IndexWorkflowCommand.of(
                        7L,
                        "sha-7",
                        IndexWorkflowTriggerType.RECOVERY,
                        "test"
                )
                .withNote("retry failed document");

        projector.project(
                IndexWorkflowState.FAILED,
                IndexWorkflowState.QUEUED,
                IndexWorkflowEvent.QUEUE,
                command
        );

        assertThat(jobRepository.queuedDocumentId).isEqualTo(7L);
        assertThat(jobRepository.updatedStageCount).isZero();
        assertThat(jobRepository.annotatedEvent).isEqualTo(IndexWorkflowEvent.QUEUE.name());
        assertThat(documentIndexingSpi.requeuedDocumentId).isEqualTo(7L);
        assertThat(documentIndexingSpi.requeueNote).isEqualTo("retry failed document");
    }

    private static final class RecordingDocumentIndexingSpi implements DocumentIndexingSpi {

        private Long requeuedDocumentId;
        private String requeueNote;

        @Override
        public Optional<DocumentIndexingView> findById(Long id) {
            return Optional.empty();
        }

        @Override
        public List<DocumentIndexingView> findPendingBefore(OffsetDateTime cutoff, int limit) {
            return List.of();
        }

        @Override
        public List<DocumentIndexingView> findProcessingBefore(OffsetDateTime cutoff, int limit) {
            return List.of();
        }

        @Override
        public List<DocumentIndexingView> findFailedBefore(OffsetDateTime cutoff, int limit) {
            return List.of();
        }

        @Override
        public List<DocumentIndexingView> findDeletingBefore(OffsetDateTime cutoff, int limit) {
            return List.of();
        }

        @Override
        public void markPending(Long id) {
        }

        @Override
        public void requeue(Long id, String note) {
            requeuedDocumentId = id;
            requeueNote = note;
        }

        @Override
        public void markProcessing(Long id) {
        }

        @Override
        public void markIndexed(Long id, Long indexedGeneration, int chunkCount, OffsetDateTime indexedAt) {
        }

        @Override
        public void markFailed(Long id, String errorMessage) {
        }

        @Override
        public void markDeleting(Long id, String note) {
        }

        @Override
        public void deleteById(Long id) {
        }
    }

    private static final class RecordingJobRepository implements RagIndexJobRepository {

        private Long queuedDocumentId;
        private int updatedStageCount;
        private String annotatedEvent;

        @Override
        public void queue(Long documentId, String contentSha256) {
            queuedDocumentId = documentId;
        }

        @Override
        public void attachMessageId(Long documentId, String contentSha256, String messageId) {
        }

        @Override
        public void annotateEvent(Long documentId, String contentSha256, String event) {
            annotatedEvent = event;
        }

        @Override
        public int startAttempt(Long documentId, String contentSha256, RagIndexStage fromStage, Long targetGeneration) {
            return 0;
        }

        @Override
        public int updateStage(Long documentId, String contentSha256, RagIndexStage fromStage, RagIndexStage toStage) {
            updatedStageCount++;
            return 1;
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
            return List.of();
        }
    }
}
