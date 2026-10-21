package io.lakeside.assistant.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** A tool offered to the model: name, natural-language description, JSON Schema of the arguments. */
public record ToolSpec(String name, String description, ObjectNode parameters) {
}
