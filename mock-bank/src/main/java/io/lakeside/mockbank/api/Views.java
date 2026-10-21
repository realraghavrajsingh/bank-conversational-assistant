package io.lakeside.mockbank.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import io.lakeside.mockbank.model.Account;
import io.lakeside.mockbank.model.Customer;
import io.lakeside.mockbank.model.ServiceRequest;
import io.lakeside.mockbank.model.Transaction;

/** JSON shapes returned by the core-banking API. Amounts are decimal strings to avoid float drift. */
public final class Views {

    private Views() {
    }

    static String money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CustomerView(String customerId, String displayName) {
        static CustomerView of(Customer c) {
            return new CustomerView(c.customerId(), c.displayName());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AccountView(String accountId, String type, String product, String maskedNumber, String currency) {
        static AccountView of(Account a) {
            return new AccountView(a.accountId(), a.type(), a.product(), a.maskedNumber(), a.currency());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BalanceView(@JsonUnwrapped AccountView account, String ledgerBalance, String availableBalance,
                              String pendingHolds, LocalDate asOf) {
        static BalanceView of(Account a, LocalDate asOf) {
            BigDecimal ledger = a.ledgerBalance();
            return new BalanceView(AccountView.of(a), money(ledger), money(ledger.subtract(a.holds())), money(a.holds()), asOf);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TransactionSummary(String transactionId, String accountId, LocalDate date, String direction,
                                     String amount, String currency, String statementText, String merchant,
                                     String category, String channel, String status) {
        static TransactionSummary of(Transaction t) {
            return new TransactionSummary(t.id(), t.accountId(), t.postedAt().toLocalDate(), t.direction(), money(t.amount()),
                    "INR", t.descriptor(), t.merchantName(), t.category(), t.channel(), t.status());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TransactionDetail(@JsonUnwrapped TransactionSummary summary, OffsetDateTime postedAt,
                                    Map<String, Object> merchantDetails, String explanation, boolean recurring,
                                    String mandateRef, String location, String balanceAfter, boolean disputeEligible) {
        static TransactionDetail of(Transaction t) {
            return new TransactionDetail(TransactionSummary.of(t), t.postedAt(), t.merchant(), t.explanation(), t.recurring(),
                    t.mandateRef(), t.location(), money(t.balanceAfter()),
                    t.isDebit() && t.channel().startsWith("Debit Card"));
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TransactionSearchResult(List<TransactionSummary> transactions, int count, String totalAmount) {
        static TransactionSearchResult of(List<Transaction> txns) {
            BigDecimal total = txns.stream().map(Transaction::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new TransactionSearchResult(txns.stream().map(TransactionSummary::of).toList(), txns.size(), money(total));
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ServiceRequestView(String serviceRequestId, String type, String accountId, int leaves, String status,
                                     OffsetDateTime createdAt, LocalDate expectedDeliveryBy, String fee,
                                     @JsonInclude(JsonInclude.Include.NON_NULL) Boolean replayed) {
        static ServiceRequestView of(ServiceRequest s) {
            return of(s, null);
        }

        static ServiceRequestView of(ServiceRequest s, Boolean replayed) {
            return new ServiceRequestView(s.id(), s.type(), s.accountId(), s.leaves(), s.status(), s.createdAt(),
                    s.expectedDeliveryBy(), money(s.fee()), replayed);
        }
    }

    public record LeafOption(int leaves, String fee) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChequeBookEligibility(String accountId, String maskedNumber, boolean eligible, String ineligibleReason,
                                        ServiceRequestView existingOpenRequest, List<LeafOption> leafOptions,
                                        String deliveryAddressMasked, int deliveryEtaDays) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChequeBookRequestBody(String accountId, Integer leaves) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String error, String message, Object detail) {
    }
}
