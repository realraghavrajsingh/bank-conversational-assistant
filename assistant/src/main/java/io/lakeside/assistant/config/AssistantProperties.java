package io.lakeside.assistant.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All tunables, bound from {@code assistant.*} (see application.yml for defaults and env vars). */
@ConfigurationProperties("assistant")
public record AssistantProperties(
        Llm llm,
        Bank bank,
        Duration sessionTtl,
        boolean demoLoginEnabled,
        int maxMessageChars,
        int maxTurnsPerMinute,
        int maxAgentSteps,
        int historyMaxMessages,
        Duration pendingActionTtl,
        String timezone) {

    public record Llm(String provider, Duration timeout, Provider gemini, Provider openai) {
    }

    public record Provider(String apiKey, String model, String baseUrl) {
    }

    public record Bank(String baseUrl, String serviceKey, Duration timeout) {
    }
}
