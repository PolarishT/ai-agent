package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class IntentRouterNodeExecutor implements WorkflowNodeExecutor {

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*}");

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final PromptTemplateService promptTemplateService;
    private final RagJsonCodec jsonCodec;

    public IntentRouterNodeExecutor(
            ObjectProvider<ChatModel> chatModelProvider,
            PromptTemplateService promptTemplateService,
            RagJsonCodec jsonCodec
    ) {
        this.chatModelProvider = chatModelProvider;
        this.promptTemplateService = promptTemplateService;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public boolean supports(NodeType type) {
        return type == NodeType.INTENT_ROUTER;
    }

    @Override
    public Map<String, Object> execute(WorkflowExecution execution, WorkflowDefinition.WorkflowNodeDefinition node) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new IllegalStateException("ChatModel is required for workflow intent_router");
        }
        String output = ChatClient.create(chatModel)
                .prompt()
                .system(promptTemplateService.load(node.promptKey()))
                .user(buildUserPrompt(execution))
                .call()
                .content();
        Map<String, Object> decision = jsonCodec.readMap(extractJson(output));
        WorkflowRuntimeState state = execution.state();
        boolean needsReset = booleanValue(decision.get("needsReset"));
        if (needsReset) {
            state.resetForNewIntent();
        }
        state.intent(intentValue(decision.get("intent")));
        state.action(stringValue(decision.get("action")));
        state.targetNode(stringValue(decision.get("targetNode")));
        state.needsReset(needsReset);
        state.status(WorkflowStatus.RUNNING);
        return Map.of("targetNode", state.targetNode(), "needsReset", needsReset, "intent", state.intent().name());
    }

    private String buildUserPrompt(WorkflowExecution execution) {
        WorkflowRuntimeState state = execution.state();
        return """
                message=%s
                currentStatus=%s
                currentSlots=%s
                candidateCount=%d
                pendingConfirmation=%s
                pendingSelection=%s
                """.formatted(
                execution.request().request().message(),
                state.status(),
                jsonCodec.write(state.slots()),
                state.cards().size(),
                state.pendingConfirmation(),
                state.pendingSelection()
        );
    }

    private String extractJson(String output) {
        if (output != null && output.trim().startsWith("{") && output.trim().endsWith("}")) {
            return output.trim();
        }
        Matcher matcher = JSON_OBJECT.matcher(output == null ? "" : output);
        if (matcher.find()) {
            return matcher.group();
        }
        throw new IllegalArgumentException("intent_router did not return JSON object");
    }

    private IntentType intentValue(Object value) {
        String text = stringValue(value);
        if (text == null) {
            return IntentType.RECOMMEND_VAGUE;
        }
        return switch (text) {
            case "product_compare" -> IntentType.COMPARE;
            case "order_create", "human_confirm", "inventory_check" -> IntentType.CART_OP;
            case "product_filter" -> IntentType.REFINE;
            case "out_of_scope" -> IntentType.OUT_OF_SCOPE;
            default -> IntentType.FILTER_BY_ATTR;
        };
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
