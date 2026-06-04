package com.bytedance.ai.graph.intent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainIntentWorkflowMappingTest {

    @ParameterizedTest
    @EnumSource(MainIntent.class)
    void mapsEveryMainIntentToWorkflow(MainIntent intent) {
        assertThat(MainIntentWorkflowMapping.targetWorkflowOf(intent)).isNotBlank();
    }

    @Test
    void reviewSummaryRoutesThroughProductQueryWorkflow() {
        assertThat(MainIntentWorkflowMapping.targetWorkflowOf(MainIntent.REVIEW_SUMMARY))
                .isEqualTo("product_query_workflow");
    }
}
