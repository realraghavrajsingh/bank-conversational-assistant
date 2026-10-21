package io.lakeside.assistant.agent;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.lakeside.assistant.guard.Grounding;
import io.lakeside.assistant.llm.ChatMessage;
import io.lakeside.assistant.llm.LlmClient;
import io.lakeside.assistant.llm.LlmException;
import io.lakeside.assistant.llm.LlmResponse;
import io.lakeside.assistant.llm.ToolCall;
import io.lakeside.assistant.session.ConversationSession;
import io.lakeside.assistant.tools.BankingTools;
import io.lakeside.assistant.tools.ToolContext;
import io.lakeside.assistant.tools.ToolOutcome;

/**
 * Conversation orchestrator. One customer turn is a bounded reason-act loop:
 * <pre>
 *   model decides -> (tool calls -> results back to the model)* -> final reply -> grounding guard -> customer
 * </pre>
 * Routing is done by the model choosing tools from their descriptions; there is no keyword or
 * if/else intent matching. The server keeps the hard guarantees: identity scoping, write
 * confirmation, grounded figures and a step limit.
 *
 * <p>Callers must hold the session's lock for the duration of {@link #handleTurn}.
 */
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    public static final String FALLBACK_UNGROUNDED_REPLY =
            "Sorry, I couldn't verify those details just now. Please ask again, or check the Accounts section of the app.";
    public static final String FALLBACK_LLM_REPLY =
            "Sorry, I'm having trouble right now. Please try again in a moment, or use the mobile app for balances, "
                    + "transactions and cheque book requests.";
    public static final String FALLBACK_STEPS_REPLY =
            "Sorry, I couldn't complete that. Could you rephrase or tell me a bit more about what you need?";

    private final LlmClient llm;
    private final BankingTools tools;
    private final ObjectMapper mapper;
    private final int maxSteps;
    private final int historyMaxMessages;
    private final Clock clock;

    public Agent(LlmClient llm, BankingTools tools, ObjectMapper mapper, int maxSteps, int historyMaxMessages, Clock clock) {
        this.llm = llm;
        this.tools = tools;
        this.mapper = mapper;
        this.maxSteps = maxSteps;
        this.historyMaxMessages = historyMaxMessages;
        this.clock = clock;
    }

    public TurnResult handleTurn(ConversationSession session, String userText) {
        int turn = session.nextTurn();
        session.messages().add(ChatMessage.user(userText));
        ToolContext ctx = new ToolContext(session);
        String system = Prompts.system(session, ZonedDateTime.now(clock));
        List<TraceEntry> trace = new ArrayList<>();
        ChatMessage correction = null;
        boolean corrected = false;

        for (int step = 0; step < maxSteps; step++) {
            List<ChatMessage> convo = new ArrayList<>(window(session.messages()));
            if (correction != null) {
                convo.add(correction);
            }
            LlmResponse resp;
            try {
                resp = llm.complete(system, convo, tools.specs());
            } catch (LlmException e) {
                log.warn("llm_error turn={} step={} err={}", turn, step, e.getMessage());
                return finish(session, Agent.FALLBACK_LLM_REPLY, TurnResult.FALLBACK_LLM, trace);
            }

            if (!resp.toolCalls().isEmpty()) {
                correction = null;
                session.messages().add(ChatMessage.assistant(resp.text(), resp.toolCalls(), resp.providerRaw(), llm.name()));
                for (ToolCall call : resp.toolCalls()) {
                    long started = System.nanoTime();
                    ToolOutcome out = tools.execute(call.name(), call.arguments(), ctx);
                    long ms = (System.nanoTime() - started) / 1_000_000;
                    if (out.ok()) {
                        Grounding.collectEvidence(out.result(), session.evidenceAmounts(), session.evidenceRefs());
                    }
                    trace.add(new TraceEntry(call.name(), call.arguments(), out.ok(), ms, out.errorCode()));
                    log.info("tool turn={} name={} ok={} ms={}", turn, call.name(), out.ok(), ms);
                    session.messages().add(ChatMessage.tool(call.id(), call.name(), toJson(out)));
                }
                continue;
            }

            String text = resp.text() == null ? "" : resp.text().strip();
            if (text.isEmpty()) {
                continue;
            }
            Grounding.Result verdict = Grounding.check(text, session.evidenceAmounts(), session.evidenceRefs());
            if (verdict.ok()) {
                session.messages().add(ChatMessage.assistant(text, List.of(), resp.providerRaw(), llm.name()));
                return new TurnResult(text, TurnResult.ANSWERED, trace);
            }
            log.warn("ungrounded_reply turn={} unsupported={}", turn, verdict.all());
            if (corrected) {
                return finish(session, FALLBACK_UNGROUNDED_REPLY, TurnResult.FALLBACK_UNGROUNDED, trace);
            }
            // One self-correction attempt. The note is transient: it is never stored in history.
            corrected = true;
            correction = ChatMessage.user("[system check] Your draft reply contained figures or references that no tool "
                    + "returned: " + String.join(", ", verdict.all()) + ". Call the appropriate tool to fetch them, "
                    + "or answer without them. Do not mention this check.");
        }
        return finish(session, FALLBACK_STEPS_REPLY, TurnResult.FALLBACK_STEPS, trace);
    }

    /** Trims history at a user-message boundary so tool call / result pairs stay intact. */
    private List<ChatMessage> window(List<ChatMessage> messages) {
        if (messages.size() <= historyMaxMessages) {
            return messages;
        }
        int start = messages.size() - historyMaxMessages;
        while (start < messages.size() && messages.get(start).role() != ChatMessage.Role.USER) {
            start++;
        }
        return messages.subList(start, messages.size());
    }

    /** Ends the turn with a fallback, keeping history well-formed for every provider. */
    private static TurnResult finish(ConversationSession session, String reply, String outcome, List<TraceEntry> trace) {
        session.messages().add(ChatMessage.assistant(reply));
        return new TurnResult(reply, outcome, trace);
    }

    private String toJson(ToolOutcome out) {
        try {
            return mapper.writeValueAsString(out.result());
        } catch (Exception e) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }
}
