package com.involutionhell.backend.rag.retrieval.web;

import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.rag.retrieval.api.RagConversationListView;
import com.involutionhell.backend.rag.retrieval.api.RagConversationMessagesView;
import com.involutionhell.backend.rag.retrieval.api.RagConversationSummaryView;
import com.involutionhell.backend.rag.retrieval.api.RagConversationUpdateRequest;
import com.involutionhell.backend.rag.retrieval.application.RagConversationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping(value = "/public/rag/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
public class RagConversationController {

    private final RagConversationService conversationService;

    public RagConversationController(RagConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public ApiResponse<RagConversationListView> listConversations(
            @RequestParam @NotBlank(message = "userId 不能为空") String userId,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @RequestParam(required = false) String cursor
    ) {
        return ApiResponse.ok(conversationService.listConversations(userId, limit, cursor));
    }

    @GetMapping("/{conversationId}/messages")
    public ApiResponse<RagConversationMessagesView> getMessages(
            @PathVariable @NotBlank(message = "conversationId 不能为空") String conversationId,
            @RequestParam @NotBlank(message = "userId 不能为空") String userId
    ) {
        return ApiResponse.ok(conversationService.getMessages(userId, conversationId));
    }

    @PostMapping(value = "/{conversationId}/update", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RagConversationSummaryView> updateConversation(
            @PathVariable @NotBlank(message = "conversationId 不能为空") String conversationId,
            @Valid @RequestBody RagConversationUpdateRequest request
    ) {
        return ApiResponse.ok(conversationService.updateConversation(conversationId, request));
    }
}
