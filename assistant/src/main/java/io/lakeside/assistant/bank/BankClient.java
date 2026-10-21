package io.lakeside.assistant.bank;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP client for the core-banking API. The customer id passed in always comes from the
 * authenticated session, never from the model.
 */
public class BankClient {

    public static final String SERVICE_KEY_HEADER = "X-Service-Key";

    private final RestClient http;
    private final ObjectMapper mapper;

    public BankClient(RestClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    public record TransactionSearch(String accountId, String direction, String query, BigDecimal minAmount,
                                    BigDecimal maxAmount, LocalDate dateFrom, LocalDate dateTo, int limit) {
    }

    public JsonNode customer(String customerId) {
        return get("/v1/customers/{c}", customerId);
    }

    public JsonNode accounts(String customerId) {
        return get("/v1/customers/{c}/accounts", customerId).path("accounts");
    }

    public JsonNode balance(String customerId, String accountId) {
        return get("/v1/customers/{c}/accounts/{a}/balance", customerId, accountId);
    }

    public JsonNode searchTransactions(String customerId, TransactionSearch s) {
        return call(() -> handle(http.get().uri(b -> {
            UriBuilder u = b.path("/v1/customers/{c}/transactions").queryParam("limit", s.limit());
            if (s.accountId() != null) u.queryParam("account_id", s.accountId());
            if (s.direction() != null) u.queryParam("direction", s.direction());
            if (s.query() != null && !s.query().isBlank()) u.queryParam("q", s.query());
            if (s.minAmount() != null) u.queryParam("min_amount", s.minAmount().toPlainString());
            if (s.maxAmount() != null) u.queryParam("max_amount", s.maxAmount().toPlainString());
            if (s.dateFrom() != null) u.queryParam("date_from", s.dateFrom().toString());
            if (s.dateTo() != null) u.queryParam("date_to", s.dateTo().toString());
            return u.build(customerId);
        }).retrieve()).body(JsonNode.class));
    }

    public JsonNode transaction(String customerId, String transactionId) {
        return get("/v1/customers/{c}/transactions/{t}", customerId, transactionId);
    }

    public JsonNode chequeBookEligibility(String customerId, String accountId) {
        return get("/v1/customers/{c}/accounts/{a}/cheque-book/eligibility", customerId, accountId);
    }

    public JsonNode createChequeBookRequest(String customerId, String accountId, int leaves, String idempotencyKey) {
        return call(() -> handle(http.post()
                .uri("/v1/customers/{c}/service-requests/cheque-book", customerId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("account_id", accountId, "leaves", leaves))
                .retrieve()).body(JsonNode.class));
    }

    public JsonNode serviceRequests(String customerId, String type) {
        return call(() -> handle(http.get().uri(b -> {
            UriBuilder u = b.path("/v1/customers/{c}/service-requests");
            if (type != null) u.queryParam("type", type);
            return u.build(customerId);
        }).retrieve()).body(JsonNode.class)).path("service_requests");
    }

    public JsonNode demoCustomers() {
        return get("/v1/demo/customers").path("customers");
    }

    // ---------------- plumbing ----------------

    private JsonNode get(String uri, Object... vars) {
        return call(() -> handle(http.get().uri(uri, vars).retrieve()).body(JsonNode.class));
    }

    private RestClient.ResponseSpec handle(RestClient.ResponseSpec spec) {
        return spec.onStatus(HttpStatusCode::isError, (request, response) -> {
            int status = response.getStatusCode().value();
            if (status >= 500) {
                throw new BankUnavailableException("core banking returned " + status, null);
            }
            JsonNode body;
            try {
                body = mapper.readTree(response.getBody().readAllBytes());
            } catch (IOException e) {
                body = mapper.createObjectNode();
            }
            throw new BankApiError(status, body.path("error").asText(""), body.path("message").asText(""), body.get("detail"));
        });
    }

    private JsonNode call(Supplier<JsonNode> request) {
        try {
            JsonNode result = request.get();
            return result == null ? mapper.createObjectNode() : result;
        } catch (BankApiError | BankUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            if (e.getCause() instanceof BankApiError api) throw api;
            if (e.getCause() instanceof BankUnavailableException down) throw down;
            throw new BankUnavailableException("core banking unreachable: " + e.getMessage(), e);
        }
    }
}
