package io.lakeside.mockbank.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

public record Transaction(
        String id,
        String accountId,
        OffsetDateTime postedAt,
        String direction,
        BigDecimal amount,
        String descriptor,
        String channel,
        Map<String, Object> merchant,
        String explanation,
        String status,
        boolean recurring,
        String mandateRef,
        String location,
        BigDecimal balanceAfter) {

    public boolean isDebit() {
        return "debit".equals(direction);
    }

    public boolean isPosted() {
        return "posted".equals(status);
    }

    public BigDecimal signedAmount() {
        return isDebit() ? amount.negate() : amount;
    }

    public String merchantName() {
        return stringValue("name");
    }

    public String category() {
        return stringValue("category");
    }

    private String stringValue(String key) {
        Object v = merchant.get(key);
        return v == null ? null : v.toString();
    }
}
