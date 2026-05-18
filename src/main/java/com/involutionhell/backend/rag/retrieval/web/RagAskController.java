package com.involutionhell.backend.rag.retrieval.web;

import com.involutionhell.backend.rag.retrieval.api.RagAskStreamEvent;
import com.involutionhell.backend.rag.retrieval.api.RagAskFacade;
import com.involutionhell.backend.rag.retrieval.api.RagAskRequest;
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
@RequestMapping("/public/rag")
@Validated
public class RagAskController {

    private final RagAskFacade ragAskFacade;

    public RagAskController(RagAskFacade ragAskFacade) {
        this.ragAskFacade = ragAskFacade;
    }

    @PostMapping(value = "/ask", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> ask(@Valid @RequestBody RagAskRequest request) {
        return ragAskFacade.askStream(request).map(this::toServerSentEvent);
    }

    private ServerSentEvent<Object> toServerSentEvent(RagAskStreamEvent event) {
        return ServerSentEvent.builder(event.data())
                .id(event.id())
                .event(event.event())
                .build();
    }
}
