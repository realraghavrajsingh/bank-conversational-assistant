package io.lakeside.assistant.web;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import io.lakeside.assistant.agent.TraceEntry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request / response bodies of the assistant API. */
final class Dtos {

    private Dtos() {
    }

    record LoginRequest(@JsonProperty("customer_id") @NotBlank @Pattern(regexp = "CUST-\\d{4}") String customerId) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record LoginResponse(String token, String displayName, long expiresIn) {
    }

    record ChatRequest(@NotBlank String message) {
    }

    record ChatResponse(String reply, String outcome, int turn, List<TraceEntry> trace) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record HealthResponse(String status, String llm, String llmProblem) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ErrorBody(String error, String message) {
    }
}
