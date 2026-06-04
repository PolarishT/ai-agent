package com.bytedance.ai.graph.intent;

import com.bytedance.ai.graph.intent.service.MainIntentPromptFactory;
import com.bytedance.ai.graph.intent.config.IntentLlmProperties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MainIntentPromptFactoryTest {

    @Test
    void promptContainsRoutingRulesAllIntentsAndJsonSchema() {
        MainIntentPromptFactory factory = new MainIntentPromptFactory(new IntentLlmProperties());
        factory.init();
        String prompt = factory.build("这个多少钱？", "");

        assertThat(prompt).contains("must NOT decide whether retrieval is needed");
        assertThat(prompt).contains("You must NOT call tools");
        assertThat(prompt).contains("Direct product ordering is not allowed");
        assertThat(prompt).contains("Only choose CREATE_ORDER for explicit cart checkout language");
        assertThat(prompt).contains("The downstream backend will validate intent, targetWorkflow, writeAction, slots, and missingSlots");
        assertThat(prompt).contains("这个多少钱？");
        assertThat(prompt).doesNotContain("{{userMessage}}", "{{conversationMemory}}");
        for (MainIntent intent : MainIntent.values()) {
            assertThat(prompt).contains(intent.name());
        }
        assertThat(prompt)
                .contains("\"intent\"")
                .contains("\"confidence\"")
                .contains("\"needClarify\"")
                .contains("\"writeAction\"")
                .contains("\"targetWorkflow\"")
                .contains("\"reason\"")
                .contains("\"slots\"")
                .contains("\"missingSlots\"");
    }
}
