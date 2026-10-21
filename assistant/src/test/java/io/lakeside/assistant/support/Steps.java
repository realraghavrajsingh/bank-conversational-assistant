package io.lakeside.assistant.support;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.lakeside.assistant.llm.ChatMessage;
import io.lakeside.assistant.llm.LlmException;
import io.lakeside.assistant.llm.LlmResponse;
import io.lakeside.assistant.llm.ToolCall;

/** Building blocks for scripted model behaviour. */
public final class Steps {

    private static final AtomicInteger IDS = new AtomicInteger();

    private Steps() {
    }

    /** The model calls a tool with fixed arguments. */
    public static ScriptedLlmClient.Step call(String tool, Map<String, ?> args) {
        return msgs -> LlmResponse.toolCalls(List.of(toolCall(tool, Fixtures.MAPPER.valueToTree(args))));
    }

    public static ScriptedLlmClient.Step call(String tool) {
        return call(tool, Map.of());
    }

    /** The model calls a tool with arguments derived from the latest tool result. */
    public static ScriptedLlmClient.Step callWith(String tool, Function<JsonNode, Map<String, ?>> args) {
        return msgs -> LlmResponse.toolCalls(List.of(toolCall(tool, Fixtures.MAPPER.valueToTree(args.apply(lastToolResult(msgs))))));
    }

    /** The model answers with fixed text. */
    public static ScriptedLlmClient.Step say(String text) {
        return msgs -> LlmResponse.text(text);
    }

    /** The model answers with text built from the latest tool result. */
    public static ScriptedLlmClient.Step sayWith(Function<JsonNode, String> text) {
        return msgs -> LlmResponse.text(text.apply(lastToolResult(msgs)));
    }

    public static ScriptedLlmClient.Step fail(String message) {
        return msgs -> {
            throw new LlmException(message);
        };
    }

    public static JsonNode lastToolResult(List<ChatMessage> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).role() == ChatMessage.Role.TOOL) {
                try {
                    return Fixtures.MAPPER.readTree(msgs.get(i).content());
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
        }
        throw new AssertionError("no tool result in conversation");
    }

    private static ToolCall toolCall(String tool, ObjectNode args) {
        return new ToolCall("c" + IDS.incrementAndGet(), tool, args);
    }
}
