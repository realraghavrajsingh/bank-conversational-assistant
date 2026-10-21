package io.lakeside.assistant.tools;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Typed, validated tool arguments. Model output is deserialised into these and checked with
 * Bean Validation before any handler runs; failures go back to the model as structured errors.
 * None of them carries a customer id: identity always comes from the session.
 */
final class ToolArgs {

    private ToolArgs() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NoArgs() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Balance(@NotBlank String accountId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record SearchTransactions(
            String accountId,
            @Pattern(regexp = "debit|credit", message = "must be 'debit' or 'credit'") String direction,
            @Size(max = 100) String query,
            @DecimalMin("0") BigDecimal minAmount,
            @DecimalMin("0") BigDecimal maxAmount,
            LocalDate dateFrom,
            LocalDate dateTo,
            @Min(1) @Max(25) Integer limit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record TransactionDetails(@NotBlank @Pattern(regexp = "TXN-\\d+", message = "must look like TXN-12345") String transactionId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record PrepareChequeBook(@NotBlank String accountId, Integer leaves) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record SubmitChequeBook(@NotBlank @Pattern(regexp = "PA-[0-9a-f]+", message = "must be a pending_request_id from prepare_cheque_book_request") String pendingRequestId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ServiceRequests(@Pattern(regexp = "cheque_book") String type) {
    }
}
