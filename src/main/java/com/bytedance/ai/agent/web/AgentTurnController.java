package com.bytedance.ai.agent.web;

import com.bytedance.ai.agent.api.AgentStreamEvent;
import com.bytedance.ai.agent.api.AgentTurnFacade;
import com.bytedance.ai.agent.api.AgentTurnRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@Validated
@RequestMapping("/public/agent")
public class AgentTurnController {

    private final AgentTurnFacade agentTurnFacade;

    public AgentTurnController(AgentTurnFacade agentTurnFacade) {
        this.agentTurnFacade = agentTurnFacade;
    }

    @GetMapping(value = "/turn", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> turnStream(
            @RequestParam @NotBlank @Size(max = 64) String userId,
            @RequestParam @NotBlank @Size(max = 64) String conversationId,
            @RequestParam @NotBlank @Size(max = 2000) String message,
            @RequestParam(required = false) @Size(max = 64) String turnId,
            @RequestParam(required = false) @Size(max = 64) String requestId,
            @RequestParam(required = false) @Size(max = 64) String imageRef
    ) {
        AgentTurnRequest request = new AgentTurnRequest(
                userId,
                conversationId,
                message,
                turnId,
                requestId,
                imageRef,
                List.of()
        );
        return agentTurnFacade.turnStream(request).map(this::toSse);
    }

    private ServerSentEvent<Object> toSse(AgentStreamEvent event) {
        return ServerSentEvent.builder(event.data())
                .id(event.id())
                .event(event.event())
                .comment(event.correlationId())
                .build();
    }
}
