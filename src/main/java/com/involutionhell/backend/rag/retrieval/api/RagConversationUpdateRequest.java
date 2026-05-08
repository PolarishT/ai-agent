package com.involutionhell.backend.rag.retrieval.api;

import jakarta.validation.constraints.NotBlank;

public record RagConversationUpdateRequest(
        @NotBlank(message = "userId 不能为空")
        String userId,
        String title,
        String status
) {
}
