package io.lakeside.assistant.llm;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

public record LlmResponse(String text, List<ToolCall> toolCalls, JsonNode providerRaw, Map<String, Integer> usage) {

    public static LlmResponse text(String text) {
        return new LlmResponse(text, List.of(), null, Map.of());
    }

    public static LlmResponse toolCalls(List<ToolCall> calls) {
        return new LlmResponse(null, List.copyOf(calls), null, Map.of());
    }
}
