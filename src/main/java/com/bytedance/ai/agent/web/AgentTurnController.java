package com.bytedance.ai.agent.web;

import com.bytedance.ai.agent.api.AgentStreamEvent;
import com.bytedance.ai.agent.api.AgentTurnFacade;
import com.bytedance.ai.agent.api.AgentTurnRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@Validated
@RequestMapping("/public/agent")
public class AgentTurnController {

    private final AgentTurnFacade agentTurnFacade;

    public AgentTurnController(AgentTurnFacade agentTurnFacade) {
        this.agentTurnFacade = agentTurnFacade;
    }

    @PostMapping(value = "/turn", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> turn(@Valid @RequestBody AgentTurnRequest request) {
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
