package com.involutionhell.backend.rag.retrieval.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * RAG 问答请求。
 *
 * @param userId 业务用户 ID
 * @param conversationId 前端生成并由外部预置的会话 ID
 * @param question 用户问题
 * @param topK 希望返回的上下文数量
 * @param sourceUriPrefix 按来源 URI 前缀过滤
 * @param tags 按文档标签过滤
 * @param headingPathContains 按标题路径关键字过滤
 * @param history 对话历史消息列表，供 query compression 使用
 * @param requestId 客户端生成的幂等请求 ID；同一用户同一会话内重复提交时复用既有问答轮次
 */
public record RagAskRequest(
        @NotBlank(message = "userId 不能为空")
        String userId,
        @NotBlank(message = "conversationId 不能为空")
        String conversationId,
        @NotBlank(message = "问题不能为空")
        String question,
        @Min(value = 1, message = "topK 最小为 1")
        @Max(value = 10, message = "topK 最大为 10")
        Integer topK,
        String sourceUriPrefix,
        List<String> tags,
        String headingPathContains,
        List<@Valid RagConversationMessage> history,
        String requestId
) {
    public RagAskRequest(
            String userId,
            String conversationId,
            String question,
            Integer topK,
            String sourceUriPrefix,
            List<String> tags,
            String headingPathContains,
            List<@Valid RagConversationMessage> history
    ) {
        this(userId, conversationId, question, topK, sourceUriPrefix, tags, headingPathContains, history, null);
    }
}
