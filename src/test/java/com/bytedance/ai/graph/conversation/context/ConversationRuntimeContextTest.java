package com.bytedance.ai.graph.conversation.context;

import com.bytedance.ai.graph.conversation.ConversationMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationRuntimeContextTest {

    @Test
    void agentMemoryTextIsEmptyWhenNothingLoaded() {
        ConversationRuntimeContext ctx = ConversationRuntimeContext.empty(1L, "u1", "c1", List.of());

        assertThat(ctx.agentMemoryText()).isEmpty();
    }

    @Test
    void agentMemoryTextRendersMessagesAndChains() {
        ConversationMessage userMsg = new ConversationMessage(
                1L, "msg-1", 1L, "USER", "我想买防晒霜",
                "SUCCEEDED", "corr-1", 1, OffsetDateTime.now()
        );
        ConversationMessage assistantMsg = new ConversationMessage(
                2L, "msg-2", 1L, "ASSISTANT", "好的，给你推荐几款",
                "SUCCEEDED", "corr-1", 2, OffsetDateTime.now()
        );
        ConversationRuntimeContext.UserGoal goal = new ConversationRuntimeContext.UserGoal(
                "PRODUCT_DISCOVERY", "找防晒霜", false, "RUNNING"
        );
        ConversationRuntimeContext.TaskStep step1 = new ConversationRuntimeContext.TaskStep(
                1, "step-1", "PRODUCT_SEARCH", "搜",
                "product_query_workflow", "SUCCEEDED",
                "turn_1_000001", LocalDateTime.now(), LocalDateTime.now(),
                new ConversationRuntimeContext.StepOutput("PRODUCT_CANDIDATES",
                        Map.of("count", 3))
        );
        ConversationRuntimeContext.TaskStep step2 = new ConversationRuntimeContext.TaskStep(
                2, "step-2", "CART_ADD", "加车",
                "cart_manage_workflow", "PENDING",
                null, null, null, null
        );
        ConversationRuntimeContext.TaskChain chain = new ConversationRuntimeContext.TaskChain(
                10L, "chain-001", 1, "EXECUTING",
                goal, List.of(step1, step2),
                "turn_1_000001", "turn_1_000001",
                LocalDateTime.now(), LocalDateTime.now()
        );
        ConversationRuntimeContext ctx = new ConversationRuntimeContext(
                1L, "u1", "c1", List.of(userMsg, assistantMsg),
                null, List.of(), null, null, null, null,
                Map.of(), Map.of(), List.of(chain)
        );

        String text = ctx.agentMemoryText();

        assertThat(text)
                .contains("[Recent messages]")
                .contains("USER: 我想买防晒霜")
                .contains("ASSISTANT: 好的，给你推荐几款")
                .contains("[Task chains]")
                .contains("chain chain-001 (EXECUTING): 找防晒霜")
                .contains("step 1 [PRODUCT_SEARCH @product_query_workflow] SUCCEEDED -> PRODUCT_CANDIDATES")
                .contains("step 2 [CART_ADD @cart_manage_workflow] PENDING");
    }

    @Test
    void agentMemoryTextWorksWithOnlyChainsNoMessages() {
        ConversationRuntimeContext.TaskChain chain = new ConversationRuntimeContext.TaskChain(
                null, "chain-x", 1, "PLANNING",
                null, List.of(), null, null,
                LocalDateTime.now(), LocalDateTime.now()
        );
        ConversationRuntimeContext ctx = new ConversationRuntimeContext(
                1L, "u1", "c1", List.of(),
                null, List.of(), null, null, null, null,
                Map.of(), Map.of(), List.of(chain)
        );

        String text = ctx.agentMemoryText();

        assertThat(text)
                .doesNotContain("[Recent messages]")
                .contains("[Task chains]")
                .contains("chain chain-x (PLANNING)");
    }

    @Test
    void conversationMemoryTextStillWorksAfterTaskChainAdditions() {
        ConversationMessage msg = new ConversationMessage(
                1L, "m", 1L, "USER", "hi",
                "SUCCEEDED", null, 1, OffsetDateTime.now()
        );
        ConversationRuntimeContext ctx = new ConversationRuntimeContext(
                1L, "u1", "c1", List.of(msg),
                null, List.of(), null, null, null, null,
                Map.of(), Map.of(), List.of()
        );

        assertThat(ctx.conversationMemoryText()).isEqualTo("USER: hi");
    }
}
