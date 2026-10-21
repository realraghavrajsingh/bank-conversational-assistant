package io.lakeside.assistant.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import io.lakeside.assistant.llm.ChatMessage;
import io.lakeside.assistant.llm.LlmClient;
import io.lakeside.assistant.llm.LlmResponse;
import io.lakeside.assistant.llm.ToolSpec;

/**
 * Deterministic stand-in for the model. Each step is a function of the conversation so far,
 * so tests drive the real orchestrator, tools, guards and mock bank without network access
 * or model non-determinism.
 */
public class ScriptedLlmClient implements LlmClient {

    public interface Step extends Function<List<ChatMessage>, LlmResponse> {
    }

    private final Deque<Step> steps = new ArrayDeque<>();
    private final List<List<ChatMessage>> calls = new ArrayList<>();

    public ScriptedLlmClient(Step... steps) {
        script(steps);
    }

    public synchronized ScriptedLlmClient script(Step... next) {
        steps.clear();
        calls.clear();
        steps.addAll(List.of(next));
        return this;
    }

    public synchronized List<List<ChatMessage>> calls() {
        return calls;
    }

    @Override
    public String name() {
        return "scripted";
    }

    @Override
    public synchronized LlmResponse complete(String system, List<ChatMessage> messages, List<ToolSpec> tools) {
        calls.add(List.copyOf(messages));
        if (steps.isEmpty()) {
            throw new AssertionError("ScriptedLlmClient ran out of steps");
        }
        return steps.pollFirst().apply(messages);
    }
}
