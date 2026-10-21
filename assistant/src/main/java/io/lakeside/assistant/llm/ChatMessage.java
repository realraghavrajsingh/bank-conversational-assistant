package io.lakeside.assistant.llm;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One entry of the conversation history.
 *
 * @param providerRaw opaque provider-native payload of an assistant turn (e.g. Gemini content with
 *                    thought signatures), replayed verbatim to the same provider so multi-step tool
 *                    use round-trips losslessly
 */
public record ChatMessage(
        Role role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String toolName,
        JsonNode providerRaw,
        String provider) {

    public enum Role { USER, ASSISTANT, TOOL }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text, List.of(), null, null, null, null);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage(Role.ASSISTANT, text, List.of(), null, null, null, null);
    }

    public static ChatMessage assistant(String text, List<ToolCall> toolCalls, JsonNode providerRaw, String provider) {
        return new ChatMessage(Role.ASSISTANT, text, List.copyOf(toolCalls), null, null, providerRaw, provider);
    }

    public static ChatMessage tool(String toolCallId, String toolName, String resultJson) {
        return new ChatMessage(Role.TOOL, resultJson, List.of(), toolCallId, toolName, null, null);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
