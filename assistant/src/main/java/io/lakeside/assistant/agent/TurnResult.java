package io.lakeside.assistant.agent;

import java.util.List;

/**
 * @param outcome {@code answered}, or the fallback that was used: {@code fallback_ungrounded},
 *                {@code fallback_llm}, {@code fallback_steps}
 */
public record TurnResult(String reply, String outcome, List<TraceEntry> trace) {

    public static final String ANSWERED = "answered";
    public static final String FALLBACK_UNGROUNDED = "fallback_ungrounded";
    public static final String FALLBACK_LLM = "fallback_llm";
    public static final String FALLBACK_STEPS = "fallback_steps";
}
