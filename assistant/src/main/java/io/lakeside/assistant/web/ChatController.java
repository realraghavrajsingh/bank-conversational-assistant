package io.lakeside.assistant.web;

import java.time.Clock;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.lakeside.assistant.agent.Agent;
import io.lakeside.assistant.agent.TurnResult;
import io.lakeside.assistant.config.AssistantProperties;
import io.lakeside.assistant.llm.LlmClient;
import io.lakeside.assistant.session.ConversationSession;
import io.lakeside.assistant.session.SessionStore;
import io.lakeside.assistant.web.Dtos.ChatRequest;
import io.lakeside.assistant.web.Dtos.ChatResponse;
import io.lakeside.assistant.web.Dtos.HealthResponse;

import jakarta.validation.Valid;

@RestController
class ChatController {

    private final Agent agent;
    private final LlmClient llm;
    private final SessionStore sessions;
    private final AssistantProperties props;
    private final Clock clock;

    ChatController(Agent agent, LlmClient llm, SessionStore sessions, AssistantProperties props, Clock clock) {
        this.agent = agent;
        this.llm = llm;
        this.sessions = sessions;
        this.props = props;
        this.clock = clock;
    }

    @PostMapping("/api/chat")
    ChatResponse chat(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                      @Valid @RequestBody ChatRequest body) {
        ConversationSession session = BearerAuth.require(sessions, authorization);
        String text = body.message().strip();
        if (text.length() > props.maxMessageChars()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(413),
                    "message too long (max " + props.maxMessageChars() + " characters)");
        }
        if (llm.configurationProblem() != null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "assistant model not configured: " + llm.configurationProblem());
        }
        if (!session.allowTurn(props.maxTurnsPerMinute(), clock.instant())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many messages, please slow down");
        }
        session.lock().lock(); // one turn at a time per conversation
        try {
            TurnResult result = agent.handleTurn(session, text);
            return new ChatResponse(result.reply(), result.outcome(), session.turn(), result.trace());
        } finally {
            session.lock().unlock();
        }
    }

    @GetMapping("/api/health")
    HealthResponse health() {
        return new HealthResponse("ok", llm.name(), llm.configurationProblem());
    }
}
