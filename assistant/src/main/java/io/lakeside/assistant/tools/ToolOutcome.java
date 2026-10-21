package io.lakeside.assistant.tools;

import com.fasterxml.jackson.databind.JsonNode;

/** Result of one tool execution. Errors are data for the model, never exceptions. */
public record ToolOutcome(JsonNode result, boolean ok, String errorCode) {
}
