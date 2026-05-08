package com.involutionhell.backend.rag.indexing.workflow;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexWorkflowCommandTests {

    @Test
    void metadataIsDefensivelyCopiedAndReadOnly() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("attempt", 1);

        IndexWorkflowCommand command = new IndexWorkflowCommand(
                7L,
                "sha-7",
                IndexWorkflowTriggerType.API,
                "test",
                null,
                null,
                null,
                null,
                null,
                null,
                source
        );
        source.put("attempt", 2);

        assertThat(command.metadata()).containsEntry("attempt", 1);
        assertThatThrownBy(() -> command.metadata().put("later", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void withMetadataReturnsANewReadOnlyCommand() {
        IndexWorkflowCommand base = IndexWorkflowCommand.of(
                7L,
                "sha-7",
                IndexWorkflowTriggerType.API,
                "test"
        );

        IndexWorkflowCommand enriched = base.withMetadata("messageId", "msg-7");

        assertThat(base.metadata()).isEmpty();
        assertThat(enriched.metadata()).containsEntry("messageId", "msg-7");
        assertThatThrownBy(() -> enriched.metadata().put("later", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
