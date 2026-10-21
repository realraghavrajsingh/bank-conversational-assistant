package io.lakeside.assistant.session;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opaque bearer token -> conversation session. In-memory for the exercise; the interface is
 * small enough to back with Redis to run the assistant stateless behind a load balancer.
 */
public class SessionStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    public SessionStore(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    public Duration ttl() {
        return ttl;
    }

    public ConversationSession create(String customerId, String displayName, List<AccountRef> accounts) {
        evictExpired();
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        ConversationSession s = new ConversationSession(token, customerId, displayName, accounts, clock.instant());
        sessions.put(token, s);
        return s;
    }

    public Optional<ConversationSession> get(String token) {
        ConversationSession s = token == null ? null : sessions.get(token);
        if (s == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (expired(s, now)) {
            sessions.remove(token);
            return Optional.empty();
        }
        s.touch(now);
        return Optional.of(s);
    }

    public void delete(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    private boolean expired(ConversationSession s, Instant now) {
        return Duration.between(s.lastSeen(), now).compareTo(ttl) > 0;
    }

    private void evictExpired() {
        Instant now = clock.instant();
        sessions.values().removeIf(s -> expired(s, now));
    }
}
