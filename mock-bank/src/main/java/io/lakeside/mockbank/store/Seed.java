package io.lakeside.mockbank.store;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import io.lakeside.mockbank.model.Customer.Address;

/** Shape of {@code seed.json}. Dates are relative ({@code days_ago}) so data stays fresh. */
@JsonIgnoreProperties(ignoreUnknown = true)
record Seed(List<SeedCustomer> customers) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record SeedCustomer(String customerId, String displayName, Address registeredAddress,
                        List<SeedAccount> accounts, List<SeedServiceRequest> openServiceRequests) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record SeedAccount(String accountId, String accountNumber, String type, String product, String currency,
                       BigDecimal openingBalance, Boolean chequeBookEligible, String chequeBookIneligibleReason,
                       List<SeedTransaction> transactions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record SeedTransaction(String id, int daysAgo, String direction, BigDecimal amount, String status,
                           String descriptor, String channel, Map<String, Object> merchant, String explanation,
                           Boolean recurring, String mandateRef, String location) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record SeedServiceRequest(String srId, String type, String accountId, int leaves, String status,
                              int daysAgo, int etaDaysFromCreation) {
    }
}
