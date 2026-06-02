package com.bytedance.ai.graph.answer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerPromptFactoryTest {

    @Test
    void buildReplacesAllPlaceholders() {
        AnswerPromptFactory factory = new AnswerPromptFactory(
                "prompts/answer-generation-v1.txt", 4000, 3000);
        factory.init();

        String prompt = factory.build(new AnswerPromptFactory.AnswerPromptInput(
                "我想买防晒霜",
                "PRODUCT_SEARCH",
                "product_query_workflow",
                "{\"count\":3}",
                "[Recent messages]\nUSER: hi",
                "给你推荐 3 款"
        ));

        assertThat(prompt)
                .contains("我想买防晒霜")
                .contains("PRODUCT_SEARCH")
                .contains("product_query_workflow")
                .contains("{\"count\":3}")
                .contains("[Recent messages]")
                .contains("USER: hi")
                .contains("给你推荐 3 款");
        // 占位符全部替换掉
        assertThat(prompt).doesNotContain("{{");
    }

    @Test
    void buildHandlesNullsAsEmptyStrings() {
        AnswerPromptFactory factory = new AnswerPromptFactory(
                "prompts/answer-generation-v1.txt", 4000, 3000);
        factory.init();

        String prompt = factory.build(new AnswerPromptFactory.AnswerPromptInput(
                null, null, null, null, null, null));

        assertThat(prompt)
                .doesNotContain("{{")
                .doesNotContain("null");
    }

    @Test
    void buildTruncatesLongMemoryFromHeadKeepingTail() {
        AnswerPromptFactory factory = new AnswerPromptFactory(
                "prompts/answer-generation-v1.txt", 100, 3000);
        factory.init();
        String longMemory = "OLD".repeat(50) + "TAIL_KEEP_THIS";

        String prompt = factory.build(new AnswerPromptFactory.AnswerPromptInput(
                "u", "i", "wf", "{}", longMemory, "rf"));

        assertThat(prompt).contains("TAIL_KEEP_THIS");
        assertThat(prompt).contains("<truncated>");
    }

    @Test
    void buildTruncatesLongWorkflowResultFromTailKeepingHead() {
        AnswerPromptFactory factory = new AnswerPromptFactory(
                "prompts/answer-generation-v1.txt", 4000, 50);
        factory.init();
        String longJson = "{\"head\":\"KEEP_HEAD\",\"tail\":\"" + "X".repeat(200) + "\"}";

        String prompt = factory.build(new AnswerPromptFactory.AnswerPromptInput(
                "u", "i", "wf", longJson, "mem", "rf"));

        assertThat(prompt).contains("KEEP_HEAD");
        assertThat(prompt).contains("<truncated>");
    }

    @Test
    void initFailsWhenTemplateMissing() {
        AnswerPromptFactory factory = new AnswerPromptFactory(
                "prompts/does-not-exist.txt", 4000, 3000);

        assertThatThrownBy(factory::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void buildBeforeInitThrows() {
        AnswerPromptFactory factory = new AnswerPromptFactory(
                "prompts/answer-generation-v1.txt", 4000, 3000);

        assertThatThrownBy(() -> factory.build(
                new AnswerPromptFactory.AnswerPromptInput("u", "i", "wf", "{}", "m", "r")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not been initialized");
    }
}
