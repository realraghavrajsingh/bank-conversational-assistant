package io.lakeside.mockbank.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;

public record ServiceRequest(
        String id,
        String customerId,
        String type,
        String accountId,
        int leaves,
        String status,
        OffsetDateTime createdAt,
        LocalDate expectedDeliveryBy,
        BigDecimal fee) {

    public static final String CHEQUE_BOOK = "cheque_book";
    private static final Set<String> OPEN_STATUSES = Set.of("Submitted", "Processing", "Dispatched");

    public boolean isOpen() {
        return OPEN_STATUSES.contains(status);
    }
}
