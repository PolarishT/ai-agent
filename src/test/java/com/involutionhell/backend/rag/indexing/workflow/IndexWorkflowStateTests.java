package com.involutionhell.backend.rag.indexing.workflow;

import com.involutionhell.backend.rag.indexing.model.RagIndexJobStatus;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRecord;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IndexWorkflowStateTests {

    @Test
    void mapsLegacyFailedStageToFailedState() {
        RagIndexJobRecord record = new RagIndexJobRecord(
                1L,
                1L,
                "sha-1",
                RagIndexJobStatus.QUEUED.name(),
                "FAILED",
                0L,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );

        assertThat(IndexWorkflowState.from(record)).isEqualTo(IndexWorkflowState.FAILED);
    }
}
