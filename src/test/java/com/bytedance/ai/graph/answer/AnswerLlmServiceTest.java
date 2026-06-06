package com.bytedance.ai.graph.answer;

import com.bytedance.ai.graph.conversation.context.RuntimeContextView;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerLlmServiceTest {

    @Test
    void generateReturnsTrimmedLlmContent() {
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        AnswerLlmService service = service(
                "  已为你找到 3 款符合条件的防晒霜，价格在 60 元上下。  ",
                capturedPrompt);

        RuntimeContextView view = new RuntimeContextView(
                "c1", "u1", "turn-1", "req-1",
                List.of(new RuntimeContextView.MessageView("turn-1", "USER", "找防晒霜", null)),
                List.of(), List.of());
        String result = service.generate(new AnswerLlmService.AnswerLlmInput(
                "找防晒霜",
                "PRODUCT_SEARCH",
                "product_query_workflow",
                Map.of("count", 3, "topName", "理肤泉"),
                view,
                "给你推荐 3 款"
        ));

        assertThat(result).isEqualTo("已为你找到 3 款符合条件的防晒霜，价格在 60 元上下。");
        // prompt 里包含核心字段，确认 LLM 拿到的是完整上下文（view 以 JSON 形式注入）
        assertThat(capturedPrompt.get())
                .contains("找防晒霜")
                .contains("PRODUCT_SEARCH")
                .contains("product_query_workflow")
                .contains("理肤泉")
                .contains("给你推荐 3 款");
    }

    @Test
    void generateStreamEmitsChunksAndReturnsTrimmedContent() {
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        AnswerLlmService service = serviceWithChatModel(
                prompt -> {
                    throw new AssertionError("blocking call should not be used for stream generation");
                },
                prompt -> {
                    capturedPrompt.set(prompt.getInstructions().get(0).getText());
                    return Flux.just(
                            chatResponse("  已为你"),
                            chatResponse("\n"),
                            chatResponse("找到 3 款"),
                            chatResponse("符合条件的防晒霜。  ")
                    );
                }
        );
        List<String> chunks = new ArrayList<>();

        String result = service.generateStream(new AnswerLlmService.AnswerLlmInput(
                "找防晒霜",
                "PRODUCT_SEARCH",
                "product_query_workflow",
                Map.of("count", 3, "topName", "理肤泉"),
                null,
                "给你推荐 3 款"
        ), chunks::add);

        assertThat(chunks).containsExactly("  已为你", "\n", "找到 3 款", "符合条件的防晒霜。  ");
        assertThat(result).isEqualTo("已为你\n找到 3 款符合条件的防晒霜。");
        assertThat(capturedPrompt.get())
                .contains("找防晒霜")
                .contains("PRODUCT_SEARCH")
                .contains("理肤泉");
    }

    @Test
    void generateWrapsLlmExceptionAsAnswerLlmException() {
        AnswerLlmService service = serviceWithChatModel(prompt -> {
            throw new RuntimeException("simulated upstream timeout");
        });

        assertThatThrownBy(() -> service.generate(new AnswerLlmService.AnswerLlmInput(
                "msg", "PRODUCT_SEARCH", "wf", null, (RuntimeContextView) null, "rule")))
                .isInstanceOf(AnswerLlmService.AnswerLlmException.class)
                .hasMessageContaining("answer LLM call failed");
    }

    @Test
    void generateRejectsEmptyOrBlankContent() {
        AnswerLlmService blank = service("   ", new AtomicReference<>());
        assertThatThrownBy(() -> blank.generate(new AnswerLlmService.AnswerLlmInput(
                "msg", "PRODUCT_SEARCH", "wf", null, (RuntimeContextView) null, "rule")))
                .isInstanceOf(AnswerLlmService.AnswerLlmException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void generateHandlesNullWorkflowResult() {
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        AnswerLlmService service = service("ok", capturedPrompt);

        service.generate(new AnswerLlmService.AnswerLlmInput(
                "msg", "PRODUCT_SEARCH", "wf", null, (RuntimeContextView) null, "rule"));

        // null workflow result → 序列化为空串，prompt 里不会出现 "null"
        assertThat(capturedPrompt.get()).doesNotContain("\"null\"");
    }

    private static AnswerLlmService service(String chatResponse, AtomicReference<String> capturedPrompt) {
        return serviceWithChatModel(prompt -> {
            capturedPrompt.set(prompt.getInstructions().get(0).getText());
            return chatResponse(chatResponse);
        });
    }

    private static AnswerLlmService serviceWithChatModel(StubChatModelFn fn) {
        return serviceWithChatModel(fn, null);
    }

    private static AnswerLlmService serviceWithChatModel(
            StubChatModelFn fn,
            StubChatModelStreamFn streamFn
    ) {
        AnswerPromptFactory factory = new AnswerPromptFactory(
                "prompts/answer-generation-v1.txt", 4000, 3000);
        factory.init();
        return new AnswerLlmService(
                ChatClient.create(new StubChatModel(fn, streamFn)),
                factory,
                new RagJsonCodec(JsonMapper.builder().build())
        );
    }

    private static ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    @FunctionalInterface
    private interface StubChatModelFn {
        ChatResponse call(Prompt prompt);
    }

    @FunctionalInterface
    private interface StubChatModelStreamFn {
        Flux<ChatResponse> stream(Prompt prompt);
    }

    private record StubChatModel(StubChatModelFn fn, StubChatModelStreamFn streamFn) implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return fn.call(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            if (streamFn != null) {
                return streamFn.stream(prompt);
            }
            return Flux.just(call(prompt));
        }
    }
}
