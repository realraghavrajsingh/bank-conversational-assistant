package io.lakeside.assistant.session;

import java.time.Instant;

/**
 * A prepared-but-not-submitted write action (a cheque book order). Its id is also the
 * idempotency key sent to core banking, so retries can never create duplicates.
 */
public final class PendingAction {

    private final String id;
    private final String accountId;
    private final int leaves;
    private final int createdTurn;
    private final Instant createdAt;
    private volatile boolean consumed;

    PendingAction(String id, String accountId, int leaves, int createdTurn, Instant createdAt) {
        this.id = id;
        this.accountId = accountId;
        this.leaves = leaves;
        this.createdTurn = createdTurn;
        this.createdAt = createdAt;
    }

    public String id() {
        return id;
    }

    public String accountId() {
        return accountId;
    }

    public int leaves() {
        return leaves;
    }

    public int createdTurn() {
        return createdTurn;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean consumed() {
        return consumed;
    }

    public void markConsumed() {
        this.consumed = true;
    }
}
