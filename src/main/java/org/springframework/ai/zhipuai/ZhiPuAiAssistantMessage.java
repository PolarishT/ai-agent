package org.springframework.ai.zhipuai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.content.Media;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compatibility type for Spring AI Alibaba Graph Core 1.1.2.2, whose default serializer
 * still registers the Spring AI 1.x ZhiPuAI assistant message class.
 */
public class ZhiPuAiAssistantMessage extends AssistantMessage {

    private String reasoningContent;

    protected ZhiPuAiAssistantMessage(
            String content,
            String reasoningContent,
            Map<String, Object> properties,
            List<ToolCall> toolCalls,
            List<Media> media
    ) {
        super(content, properties, toolCalls, media);
        this.reasoningContent = reasoningContent;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public ZhiPuAiAssistantMessage setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
        return this;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ZhiPuAiAssistantMessage that)) {
            return false;
        }
        return super.equals(other) && Objects.equals(reasoningContent, that.reasoningContent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), reasoningContent);
    }

    @Override
    public String toString() {
        return "ZhiPuAiAssistantMessage{" +
                "reasoningContent='" + reasoningContent + '\'' +
                ", message=" + super.toString() +
                '}';
    }
}
