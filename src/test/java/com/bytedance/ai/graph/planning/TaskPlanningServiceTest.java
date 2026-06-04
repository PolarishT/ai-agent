package com.bytedance.ai.graph.planning;

import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskPlanningServiceTest {

    @Test
    void planParsesOrderedTasksFromLlmJson() {
        TaskPlanningService service = service("""
                {
                  "tasks": [
                    { "taskName": "搜非气泡水 10-15 元", "taskType": "PRODUCT_SEARCH", "workflow": "product_query_workflow" },
                    { "taskName": "选定商品加入购物车", "taskType": "ADD_TO_CART", "workflow": "cart_manage_workflow" }
                  ]
                }
                """);

        List<TaskPlanningService.PlannedTask> tasks =
                service.plan("买非气泡水食品饮料并加购", "PRODUCT_SEARCH", "");

        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).taskType()).isEqualTo("PRODUCT_SEARCH");
        assertThat(tasks.get(0).workflow()).isEqualTo("product_query_workflow");
        assertThat(tasks.get(1).taskType()).isEqualTo("ADD_TO_CART");
    }

    @Test
    void planSupportsAtomicSingleTask() {
        TaskPlanningService service = service("""
                { "tasks": [ { "taskName": "查价格", "taskType": "PRICE_QUERY", "workflow": "price_query_workflow" } ] }
                """);

        assertThat(service.plan("维他柠檬茶多少钱", "PRICE_QUERY", "")).hasSize(1);
    }

    @Test
    void promptRequiresCartBeforeCreateOrderForDirectProductOrders() {
        StringBuilder capturedPrompt = new StringBuilder();
        TaskPlanningService service = serviceWithModel(prompt -> {
            capturedPrompt.append(prompt.getInstructions().get(0).getText());
            return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                    { "tasks": [ { "taskName": "搜索商品", "taskType": "PRODUCT_SEARCH", "workflow": "product_query_workflow" } ] }
                    """))));
        });

        service.plan("推荐一款双肩包并下单", "PRODUCT_RECOMMEND", "");

        assertThat(capturedPrompt.toString())
                .contains("不能直接规划 CREATE_ORDER")
                .contains("只有用户明确结算已有购物车");
    }

    @Test
    void planThrowsWhenLlmFails() {
        TaskPlanningService service = serviceWithModel(prompt -> {
            throw new RuntimeException("boom");
        });

        assertThatThrownBy(() -> service.plan("x", "PRODUCT_SEARCH", ""))
                .isInstanceOf(TaskPlanningService.TaskPlanningException.class);
    }

    @Test
    void planThrowsWhenEmptyPlan() {
        TaskPlanningService service = service("{ \"tasks\": [] }");

        assertThatThrownBy(() -> service.plan("x", "PRODUCT_SEARCH", ""))
                .isInstanceOf(TaskPlanningService.TaskPlanningException.class);
    }

    private static TaskPlanningService service(String json) {
        return serviceWithModel(prompt ->
                new ChatResponse(List.of(new Generation(new AssistantMessage(json)))));
    }

    private static TaskPlanningService serviceWithModel(StubFn fn) {
        TaskPlanningService service = new TaskPlanningService(
                ChatClient.create(new StubChatModel(fn)),
                new RagJsonCodec(JsonMapper.builder().build()),
                "prompts/task-planning-v1.txt");
        service.init();
        return service;
    }

    @FunctionalInterface
    private interface StubFn {
        ChatResponse call(Prompt prompt);
    }

    private record StubChatModel(StubFn fn) implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return fn.call(prompt);
        }
    }
}
