package com.bytedance.ai.graph.web;

import com.bytedance.ai.common.ratelimit.RateLimit;
import com.bytedance.ai.common.ratelimit.RateWindow;
import com.bytedance.ai.graph.api.AgentStreamEvent;
import com.bytedance.ai.graph.api.AgentTurnRequest;
import com.bytedance.ai.graph.api.events.AnswerCompletedPayload;
import com.bytedance.ai.graph.api.events.AnswerDeltaPayload;
import com.bytedance.ai.graph.api.events.TurnErrorPayload;
import com.bytedance.ai.graph.api.events.WorkflowStartedPayload;
import com.bytedance.ai.graph.api.AgentStreamEventType;
import com.bytedance.ai.graph.api.GuideGraphFinalSummary;
import com.bytedance.ai.graph.api.GuideGraphRequest;
import com.bytedance.ai.graph.api.GuideGraphStreamFacade;
import com.bytedance.ai.graph.api.NodeRunStatus;
import com.bytedance.ai.graph.conversation.persistence.AgentConversationRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 智能导购回合接口，负责把用户请求转换成 SSE 事件流。
 */
@RestController
@Validated
@RequestMapping("/public/agent")
public class GuideAgentTurnController {

    private final GuideGraphStreamFacade graphStreamFacade;
    private final AgentConversationRepository conversationRepository;

    public GuideAgentTurnController(
            GuideGraphStreamFacade graphStreamFacade,
            AgentConversationRepository conversationRepository
    ) {
        this.graphStreamFacade = graphStreamFacade;
        this.conversationRepository = conversationRepository;
    }

    // 按「用户:会话」维度限流，保护下游 LLM/检索等昂贵调用，防止单会话刷爆配额。
    // 窗口比默认更贴合多轮导购节奏（默认 1min/6 会误伤连续追问）；超限返回 429 + Retry-After。
    @RateLimit(
            key = "#userId + ':' + #conversationId",
            windows = {
                    @RateWindow(seconds = 60, permits = 20),
                    @RateWindow(seconds = 600, permits = 120)
            },
            message = "导购对话太频繁了，请稍后再试"
    )
    @GetMapping(value = "/turn", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> turnStream(
            @RequestParam @NotBlank @Size(max = 64) String userId,
            @RequestParam @NotBlank @Size(max = 64) String conversationId,
            @RequestParam @NotBlank @Size(max = 2000) String message,
            @RequestParam(required = false) @Size(max = 64) String requestId,
            @RequestParam(required = false) @Size(max = 64) String imageRef,
            @RequestParam(required = false) @Size(max = 16) String streamMode
    ) {
        String actualTurnId = conversationRepository.allocateTurnId(userId, conversationId);
        String actualRequestId = StringUtils.hasText(requestId) ? requestId : UUID.randomUUID().toString();
        StreamMode actualStreamMode = StreamMode.from(streamMode);

        AgentTurnRequest request = new AgentTurnRequest(
                userId,
                conversationId,
                message,
                actualTurnId,
                actualRequestId,
                imageRef,
                List.of()
        );

        GuideGraphRequest graphRequest = GuideGraphRequest.from(request);

        return graphStreamFacade.turnStream(graphRequest)
                .map(event -> toSse(event, graphRequest, actualStreamMode))
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    private Optional<ServerSentEvent<Object>> toSse(
            AgentStreamEvent event,
            GuideGraphRequest request,
            StreamMode streamMode
    ) {
        if (streamMode == StreamMode.TRACE) {
            return Optional.of(rawSse(event.event(), event));
        }

        String eventName = event.event();
        if (AgentStreamEventType.TURN_STARTED.eventName().equals(eventName)) {
            return Optional.of(rawSse(eventName, event, new ProdTurnStartedPayload(
                    request.runId(),
                    request.requestId(),
                    request.conversationId()
            )));
        }
        if (AgentStreamEventType.WORKFLOW_STARTED.eventName().equals(eventName)
                && event.data() instanceof WorkflowStartedPayload payload) {
            return Optional.of(rawSse(eventName, event, payload));
        }
        if (AgentStreamEventType.ANSWER_DELTA.eventName().equals(eventName)) {
            return Optional.of(rawSse(eventName, event, new ProdAnswerDeltaPayload(answerText(event.data()))));
        }
        if (AgentStreamEventType.ANSWER_COMPLETED.eventName().equals(eventName)) {
            return Optional.of(rawSse(eventName, event, new ProdAnswerCompletedPayload(messageId(event.data()))));
        }
        if (AgentStreamEventType.TURN_COMPLETED.eventName().equals(eventName)
                && event.data() instanceof GuideGraphFinalSummary summary) {
            return Optional.of(rawSse(eventName, event, new ProdTurnCompletedPayload(
                    summary.status() == NodeRunStatus.FAILED ? "FAILED" : "SUCCESS",
                    summary.intent() == null ? null : summary.intent().name(),
                    summary.targetWorkflow(),
                    summary.requestId(),
                    summary.errorCode()
            )));
        }
        if (AgentStreamEventType.TURN_ERROR.eventName().equals(eventName) || "error".equals(eventName)) {
            TurnErrorPayload error = turnError(event.data());
            String code = error == null ? "GUIDE_GRAPH_FAILED" : error.code();
            return Optional.of(rawSse("error", event, new ProdErrorPayload(
                    safeErrorCode(code),
                    publicErrorMessage(code, error == null ? null : error.message()),
                    request.requestId(),
                    error == null || error.recoverable() || recoverableCode(code)
            )));
        }
        return Optional.empty();
    }

    private ServerSentEvent<Object> rawSse(String eventName, AgentStreamEvent event) {
        return rawSse(eventName, event, event.data());
    }

    private ServerSentEvent<Object> rawSse(String eventName, AgentStreamEvent event, Object data) {
        return ServerSentEvent.builder(data)
                .id(event.id())
                .event(eventName)
                .comment(event.correlationId())
                .build();
    }

    private String answerText(Object data) {
        if (data instanceof AnswerDeltaPayload payload) {
            return payload.text();
        }
        return data == null ? "" : String.valueOf(data);
    }

    private String messageId(Object data) {
        if (data instanceof AnswerCompletedPayload payload) {
            return payload.messageId();
        }
        return data == null ? null : String.valueOf(data);
    }

    private TurnErrorPayload turnError(Object data) {
        return data instanceof TurnErrorPayload payload ? payload : null;
    }

    private String safeErrorCode(String code) {
        return StringUtils.hasText(code) ? code : "GUIDE_GRAPH_FAILED";
    }

    private boolean recoverableCode(String code) {
        String safeCode = safeErrorCode(code);
        return safeCode.startsWith("MAIN_INTENT_") || safeCode.startsWith("GUIDE_GRAPH_");
    }

    private String publicErrorMessage(String code, String fallbackMessage) {
        return switch (safeErrorCode(code)) {
            case "MAIN_INTENT_LLM_TIMEOUT" -> "当前请求处理超时，请稍后重试。";
            case "ORDER_WORKFLOW_NOT_DISPATCHED" -> "下单流程没有被正确执行，请检查主图 workflow 路由。";
            case "MAIN_INTENT_ROUTER_FAILED", "GUIDE_GRAPH_NODE_FAILED", "GUIDE_GRAPH_FAILED" ->
                    "当前请求处理失败，请稍后重试。";
            default -> StringUtils.hasText(fallbackMessage)
                    ? fallbackMessage
                    : "当前请求处理失败，请稍后重试。";
        };
    }

    private enum StreamMode {
        PROD,
        TRACE;

        static StreamMode from(String value) {
            if (!StringUtils.hasText(value)) {
                return PROD;
            }
            return "trace".equals(value.trim().toLowerCase(Locale.ROOT)) ? TRACE : PROD;
        }
    }

    private record ProdTurnStartedPayload(
            String turnId,
            String requestId,
            String conversationId
    ) {
    }

    private record ProdAnswerDeltaPayload(String text) {
    }

    private record ProdAnswerCompletedPayload(String messageId) {
    }

    private record ProdTurnCompletedPayload(
            String status,
            String intent,
            String targetWorkflow,
            String requestId,
            String errorCode
    ) {
    }

    private record ProdErrorPayload(
            String code,
            String message,
            String requestId,
            boolean recoverable
    ) {
    }
}
