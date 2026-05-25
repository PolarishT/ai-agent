package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SlotFillingNodeExecutor implements WorkflowNodeExecutor {

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*}");

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final PromptTemplateService promptTemplateService;
    private final RagJsonCodec jsonCodec;

    public SlotFillingNodeExecutor(
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
        return type == NodeType.SLOT_FILLING;
    }

    @Override
    public Map<String, Object> execute(WorkflowExecution execution, WorkflowDefinition.WorkflowNodeDefinition node) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new IllegalStateException("ChatModel is required for workflow slot_filling");
        }
        String output = ChatClient.create(chatModel)
                .prompt()
                .system(promptTemplateService.load(node.promptKey()))
                .user("message=" + execution.request().request().message() + "\ncurrentSlots=" + jsonCodec.write(execution.state().slots()))
                .call()
                .content();
        Map<String, Object> map = jsonCodec.readMap(extractJson(output));
        Slot slots = new Slot(
                stringList(map.get("must")),
                Slot.MustNot.empty(),
                priceRange(map.get("priceRange")),
                stringValue(map.get("categoryHint")),
                stringList(map.get("brands")),
                stringValue(map.get("scenario"))
        );
        execution.state().slots(slots);
        if (Boolean.TRUE.equals(map.get("waitingSlot"))) {
            execution.state().status(WorkflowStatus.WAITING_SLOT);
            execution.state().answerText(stringValue(map.get("message")));
        }
        return Map.of("slotsEmpty", slots.isEmpty(), "status", execution.state().status().name());
    }

    private String extractJson(String output) {
        String trimmed = output == null ? "" : output.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        Matcher matcher = JSON_OBJECT.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }
        throw new IllegalArgumentException("slot_filling did not return JSON object");
    }

    private Slot.PriceRange priceRange(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return new Slot.PriceRange(decimal(map.get("min")), decimal(map.get("max")));
    }

    private BigDecimal decimal(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).filter(StringUtils::hasText).toList();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
