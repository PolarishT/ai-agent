package com.bytedance.ai.agent.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 客户端显式传入的历史消息；W1 仅作为 API 预留。
 */
public record AgentConversationTurn(
        @NotBlank(message = "role 不能为空")
        @Size(max = 16, message = "role 最长 16")
        String role,
        @NotBlank(message = "content 不能为空")
        @Size(max = 2000, message = "content 最长 2000")
        String content
) {}