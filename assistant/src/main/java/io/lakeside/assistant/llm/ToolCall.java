package io.lakeside.assistant.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** A tool invocation requested by the model. Ids starting with {@code gen_} were synthesised locally. */
public record ToolCall(String id, String name, ObjectNode arguments) {

    public static final String SYNTHETIC_ID_PREFIX = "gen_";

    public boolean hasProviderId() {
        return id != null && !id.startsWith(SYNTHETIC_ID_PREFIX);
    }
}
