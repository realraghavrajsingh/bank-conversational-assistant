package io.lakeside.mockbank.model;

import java.math.BigDecimal;
import java.util.List;

public record Account(
        String accountId,
        String customerId,
        String accountNumber,
        String type,
        String product,
        String currency,
        BigDecimal openingBalance,
        boolean chequeBookEligible,
        String chequeBookIneligibleReason,
        List<Transaction> transactions) {

    /** Opening balance plus all posted transactions; pending ones are excluded. */
    public BigDecimal ledgerBalance() {
        return transactions.stream()
                .filter(Transaction::isPosted)
                .map(Transaction::signedAmount)
                .reduce(openingBalance, BigDecimal::add);
    }

    /** Pending card authorisations already held from the available balance. */
    public BigDecimal holds() {
        return transactions.stream()
                .filter(t -> !t.isPosted() && t.isDebit())
                .map(Transaction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public String maskedNumber() {
        return "XXXX" + accountNumber.substring(accountNumber.length() - 4);
    }
}
