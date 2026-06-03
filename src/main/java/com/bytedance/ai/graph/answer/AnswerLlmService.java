package com.bytedance.ai.graph.answer;

import com.bytedance.ai.graph.conversation.context.RuntimeContextView;
import com.bytedance.ai.shared.support.RagJsonCodec;
import com.bytedance.ai.shared.support.RagLogFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * LLM 生成 answer 文本。复用 intentChatClient（temperature 0），保持简洁、可复现。
 *
 * <p>调用方典型用法：先准备 {@link AnswerLlmInput}，调 {@link #generate(AnswerLlmInput)}，
 * 拿到的纯文本直接放进 answerContext.answer。
 * LLM 异常 / 返回空时抛出 {@link AnswerLlmException}，调用方负责走规则兜底。
 */
@Service
public class AnswerLlmService {

    private static final Logger log = LoggerFactory.getLogger(AnswerLlmService.class);

    private final ChatClient chatClient;
    private final AnswerPromptFactory promptFactory;
    private final RagJsonCodec jsonCodec;

    public AnswerLlmService(
            @Qualifier("intentChatClient") ChatClient chatClient,
            AnswerPromptFactory promptFactory,
            RagJsonCodec jsonCodec
    ) {
        this.chatClient = chatClient;
        this.promptFactory = promptFactory;
        this.jsonCodec = jsonCodec;
    }

    /**
     * 生成最终回复。LLM 失败或空响应都会抛 {@link AnswerLlmException}。
     */
    public String generate(AnswerLlmInput input) {
        String workflowResultJson = serializeWorkflowResult(input.workflowResult());
        AnswerPromptFactory.AnswerPromptInput promptInput =
                new AnswerPromptFactory.AnswerPromptInput(
                        input.userMessage(),
                        input.intent(),
                        input.workflow(),
                        workflowResultJson,
                        serializeView(input.view()),
                        input.ruleFallback()
                );
        String prompt = promptFactory.build(promptInput);

        String content;
        try {
            content = chatClient.prompt(prompt).call().content();
        } catch (Exception ex) {
            log.atWarn()
                    .addKeyValue(RagLogFields.EVENT_NAME, "answer_llm.call_failed")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_FAILURE)
                    .addKeyValue("answer_llm.intent", input.intent())
                    .addKeyValue("answer_llm.workflow", input.workflow())
                    .log("answer LLM call failed: {}", ex.toString());
            throw new AnswerLlmException("answer LLM call failed", ex);
        }
        if (!StringUtils.hasText(content)) {
            log.atWarn()
                    .addKeyValue(RagLogFields.EVENT_NAME, "answer_llm.empty_response")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_FAILURE)
                    .log("answer LLM returned empty content");
            throw new AnswerLlmException("answer LLM returned empty content");
        }
        String trimmed = content.strip();
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "answer_llm.generated")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SUCCESS)
                .addKeyValue("answer_llm.intent", input.intent())
                .addKeyValue("answer_llm.workflow", input.workflow())
                .addKeyValue("answer_llm.length", trimmed.length())
                .log("answer LLM generated: intent={}, workflow={}, length={}",
                        input.intent(), input.workflow(), trimmed.length());
        return trimmed;
    }

    private String serializeWorkflowResult(Object workflowResult) {
        if (workflowResult == null) {
            return "";
        }
        try {
            return jsonCodec.write(workflowResult);
        } catch (RuntimeException ex) {
            // 工作流结果不可序列化时退化成 toString，prompt 至少能看到点信息
            log.atWarn()
                    .addKeyValue(RagLogFields.EVENT_NAME, "answer_llm.workflow_result_serialize_failed")
                    .log("workflow result not serializable to JSON, falling back to toString: {}",
                            ex.toString());
            return String.valueOf(workflowResult);
        }
    }

    /** 把运行时上下文 view 序列化成 JSON 喂给 prompt（recentMessages + taskChain + taskSummaries）。 */
    private String serializeView(RuntimeContextView view) {
        if (view == null) {
            return "";
        }
        try {
            return jsonCodec.write(view);
        } catch (RuntimeException ex) {
            log.atWarn()
                    .addKeyValue(RagLogFields.EVENT_NAME, "answer_llm.view_serialize_failed")
                    .log("runtime context view not serializable: {}", ex.toString());
            return "";
        }
    }

    /** Service 入参载体。 */
    public record AnswerLlmInput(
            String userMessage,
            String intent,
            String workflow,
            Object workflowResult,
            RuntimeContextView view,
            String ruleFallback
    ) {
    }

    /** LLM 调用失败的标记异常，方便上层捕获后走规则兜底。 */
    public static class AnswerLlmException extends RuntimeException {
        public AnswerLlmException(String message) {
            super(message);
        }

        public AnswerLlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
