package io.lakeside.assistant.agent;

import com.fasterxml.jackson.databind.JsonNode;

/** One tool call made while answering a turn; returned to the UI for transparency. */
public record TraceEntry(String tool, JsonNode arguments, boolean ok, long ms, String error) {
}
