package io.lakeside.mockbank.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.lakeside.mockbank.api.Views.AccountView;
import io.lakeside.mockbank.api.Views.BalanceView;
import io.lakeside.mockbank.api.Views.ChequeBookEligibility;
import io.lakeside.mockbank.api.Views.ChequeBookRequestBody;
import io.lakeside.mockbank.api.Views.CustomerView;
import io.lakeside.mockbank.api.Views.LeafOption;
import io.lakeside.mockbank.api.Views.ServiceRequestView;
import io.lakeside.mockbank.api.Views.TransactionDetail;
import io.lakeside.mockbank.api.Views.TransactionSearchResult;
import io.lakeside.mockbank.model.Account;
import io.lakeside.mockbank.model.Customer;
import io.lakeside.mockbank.model.ServiceRequest;
import io.lakeside.mockbank.store.BankStore;
import io.lakeside.mockbank.store.TransactionQuery;

/**
 * Core-banking API. Every resource is addressed under the customer it belongs to, and
 * accounts or transactions of another customer return 404 (no existence leak).
 */
@RestController
class BankController {

    private static final String CUSTOMER = "/v1/customers/{customerId}";

    private final BankStore store;

    BankController(BankStore store) {
        this.store = store;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "ok", "business_date", store.today().toString());
    }

    @GetMapping(CUSTOMER)
    CustomerView customer(@PathVariable String customerId) {
        return CustomerView.of(customerOr404(customerId));
    }

    @GetMapping(CUSTOMER + "/accounts")
    Map<String, List<AccountView>> accounts(@PathVariable String customerId) {
        customerOr404(customerId);
        return Map.of("accounts", store.accountsFor(customerId).stream().map(AccountView::of).toList());
    }

    @GetMapping(CUSTOMER + "/accounts/{accountId}/balance")
    BalanceView balance(@PathVariable String customerId, @PathVariable String accountId) {
        return BalanceView.of(accountOr404(customerId, accountId), store.today());
    }

    @GetMapping(CUSTOMER + "/transactions")
    TransactionSearchResult searchTransactions(
            @PathVariable String customerId,
            @RequestParam(name = "account_id", required = false) String accountId,
            @RequestParam(required = false) String direction,
            @RequestParam(name = "q", required = false) String text,
            @RequestParam(name = "min_amount", required = false) BigDecimal minAmount,
            @RequestParam(name = "max_amount", required = false) BigDecimal maxAmount,
            @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "10") int limit) {
        customerOr404(customerId);
        if (accountId != null) {
            accountOr404(customerId, accountId);
        }
        if (direction != null && !direction.equals("debit") && !direction.equals("credit")) {
            throw BankApiException.badRequest("direction must be debit or credit");
        }
        if (text != null && text.length() > 100) {
            throw BankApiException.badRequest("q must be at most 100 characters");
        }
        if (limit < 1 || limit > 50) {
            throw BankApiException.badRequest("limit must be between 1 and 50");
        }
        if ((minAmount != null && minAmount.signum() < 0) || (maxAmount != null && maxAmount.signum() < 0)) {
            throw BankApiException.badRequest("amounts must be non-negative");
        }
        TransactionQuery q = new TransactionQuery(accountId, direction, text, minAmount, maxAmount, dateFrom, dateTo, limit);
        return TransactionSearchResult.of(store.search(customerId, q));
    }

    @GetMapping(CUSTOMER + "/transactions/{transactionId}")
    TransactionDetail transaction(@PathVariable String customerId, @PathVariable String transactionId) {
        customerOr404(customerId);
        return store.transactionFor(customerId, transactionId)
                .map(TransactionDetail::of)
                .orElseThrow(() -> BankApiException.notFound("transaction"));
    }

    @GetMapping(CUSTOMER + "/accounts/{accountId}/cheque-book/eligibility")
    ChequeBookEligibility chequeBookEligibility(@PathVariable String customerId, @PathVariable String accountId) {
        Customer customer = customerOr404(customerId);
        Account account = accountOr404(customerId, accountId);
        ServiceRequest open = store.openChequeBookRequest(customerId, accountId).orElse(null);
        String reason = !account.chequeBookEligible() ? account.chequeBookIneligibleReason()
                : open != null ? "An open cheque book request already exists for this account." : null;
        List<LeafOption> options = BankStore.LEAF_OPTIONS.stream()
                .map(n -> new LeafOption(n, Views.money(BankStore.CHEQUE_FEE.get(n))))
                .toList();
        return new ChequeBookEligibility(account.accountId(), account.maskedNumber(),
                account.chequeBookEligible() && open == null, reason,
                open == null ? null : ServiceRequestView.of(open), options,
                customer.registeredAddress().masked(), BankStore.CHEQUE_ETA_DAYS);
    }

    @PostMapping(CUSTOMER + "/service-requests/cheque-book")
    @ResponseStatus(HttpStatus.CREATED)
    ServiceRequestView createChequeBookRequest(@PathVariable String customerId,
                                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @RequestBody ChequeBookRequestBody body) {
        customerOr404(customerId);
        if (idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            throw BankApiException.badRequest("Idempotency-Key must be 8-128 characters");
        }
        if (body.accountId() == null) {
            throw BankApiException.badRequest("account_id is required");
        }
        Account account = accountOr404(customerId, body.accountId());
        int leaves = body.leaves() == null ? 25 : body.leaves();
        if (!BankStore.LEAF_OPTIONS.contains(leaves)) {
            throw new BankApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_leaves",
                    "leaves must be one of " + BankStore.LEAF_OPTIONS, null);
        }
        if (!account.chequeBookEligible()) {
            throw new BankApiException(HttpStatus.CONFLICT, "not_eligible", account.chequeBookIneligibleReason(), null);
        }
        return store.createChequeBookRequest(customerId, account.accountId(), leaves, idempotencyKey)
                .map(r -> ServiceRequestView.of(r.request(), r.replayed()))
                .orElseThrow(() -> new BankApiException(HttpStatus.CONFLICT, "open_request_exists",
                        "An open cheque book request already exists for this account.",
                        store.openChequeBookRequest(customerId, account.accountId()).map(ServiceRequestView::of).orElse(null)));
    }

    @GetMapping(CUSTOMER + "/service-requests")
    Map<String, List<ServiceRequestView>> serviceRequests(@PathVariable String customerId,
                                                          @RequestParam(required = false) String type) {
        customerOr404(customerId);
        return Map.of("service_requests", store.serviceRequestsFor(customerId, type).stream().map(ServiceRequestView::of).toList());
    }

    /**
     * Demo-only directory backing the simulated sign-in screen. A real deployment would not
     * expose this; identity would come from the bank's identity provider.
     */
    @GetMapping("/v1/demo/customers")
    Map<String, List<CustomerView>> demoCustomers() {
        return Map.of("customers", store.customers().stream().map(CustomerView::of).toList());
    }

    private Customer customerOr404(String customerId) {
        return store.customer(customerId).orElseThrow(() -> BankApiException.notFound("customer"));
    }

    private Account accountOr404(String customerId, String accountId) {
        return store.accountFor(customerId, accountId).orElseThrow(() -> BankApiException.notFound("account"));
    }
}
