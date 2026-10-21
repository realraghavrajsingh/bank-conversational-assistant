package io.lakeside.assistant.session;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import io.lakeside.assistant.llm.ChatMessage;

/**
 * One conversation, bound to exactly one authenticated customer. Everything the agent does
 * is scoped through it. Turn state is only mutated while {@link #lock()} is held.
 */
public final class ConversationSession {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String token;
    private final String customerId;
    private final String displayName;
    private volatile List<AccountRef> accounts;
    private volatile Instant lastSeen;

    private int turn;
    private final List<ChatMessage> messages = new ArrayList<>();
    private final Map<String, PendingAction> pending = new LinkedHashMap<>();
    /** Every number / reference the backend returned in this conversation (see Grounding). */
    private final Set<String> evidenceAmounts = new HashSet<>();
    private final Set<String> evidenceRefs = new HashSet<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Instant> recentTurns = new ArrayDeque<>();

    public ConversationSession(String token, String customerId, String displayName, List<AccountRef> accounts, Instant now) {
        this.token = token;
        this.customerId = customerId;
        this.displayName = displayName;
        this.accounts = List.copyOf(accounts);
        this.lastSeen = now;
    }

    public String token() {
        return token;
    }

    public String customerId() {
        return customerId;
    }

    public String displayName() {
        return displayName;
    }

    public String firstName() {
        return displayName.split("\\s+")[0];
    }

    public List<AccountRef> accounts() {
        return accounts;
    }

    public void accounts(List<AccountRef> refreshed) {
        this.accounts = List.copyOf(refreshed);
    }

    public Set<String> accountIds() {
        return accounts.stream().map(AccountRef::accountId).collect(Collectors.toUnmodifiableSet());
    }

    public Instant lastSeen() {
        return lastSeen;
    }

    void touch(Instant now) {
        this.lastSeen = now;
    }

    public ReentrantLock lock() {
        return lock;
    }

    public int turn() {
        return turn;
    }

    public int nextTurn() {
        return ++turn;
    }

    public List<ChatMessage> messages() {
        return messages;
    }

    public Set<String> evidenceAmounts() {
        return evidenceAmounts;
    }

    public Set<String> evidenceRefs() {
        return evidenceRefs;
    }

    public PendingAction newPendingChequeBook(String accountId, int leaves, Instant now) {
        byte[] b = new byte[6];
        RANDOM.nextBytes(b);
        PendingAction pa = new PendingAction("PA-" + HexFormat.of().formatHex(b), accountId, leaves, turn, now);
        pending.put(pa.id(), pa);
        return pa;
    }

    public PendingAction pending(String id) {
        return pending.get(id);
    }

    public Map<String, PendingAction> pendingActions() {
        return pending;
    }

    public void removePending(String id) {
        pending.remove(id);
    }

    /** Sliding one-minute window rate limit. */
    public synchronized boolean allowTurn(int maxPerMinute, Instant now) {
        while (!recentTurns.isEmpty() && Duration.between(recentTurns.peekFirst(), now).toSeconds() >= 60) {
            recentTurns.pollFirst();
        }
        if (recentTurns.size() >= maxPerMinute) {
            return false;
        }
        recentTurns.addLast(now);
        return true;
    }
}
