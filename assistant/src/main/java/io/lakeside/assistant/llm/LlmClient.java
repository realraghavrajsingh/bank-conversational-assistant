package io.lakeside.assistant.llm;

import java.util.List;

/**
 * Provider-neutral model interface. The orchestrator only speaks this vocabulary;
 * adapters translate to each vendor's wire format, so swapping providers is configuration.
 */
public interface LlmClient {

    String name();

    LlmResponse complete(String system, List<ChatMessage> messages, List<ToolSpec> tools);

    /** Non-null when the client cannot work (e.g. missing API key); surfaced by the health endpoint. */
    default String configurationProblem() {
        return null;
    }
}
