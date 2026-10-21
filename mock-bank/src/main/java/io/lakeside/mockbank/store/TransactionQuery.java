package io.lakeside.mockbank.store;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionQuery(String accountId, String direction, String text, BigDecimal minAmount,
                               BigDecimal maxAmount, LocalDate dateFrom, LocalDate dateTo, int limit) {
}
