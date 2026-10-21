package io.lakeside.mockbank.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.lakeside.mockbank.model.Account;
import io.lakeside.mockbank.model.Customer;
import io.lakeside.mockbank.model.ServiceRequest;
import io.lakeside.mockbank.model.Transaction;

/**
 * In-memory system of record seeded from {@code seed.json}. All reads are scoped by customer id;
 * a resource belonging to another customer is indistinguishable from one that does not exist.
 */
@Component
public class BankStore {

    public static final List<Integer> LEAF_OPTIONS = List.of(25, 50);
    /** 25 leaves free; 50 leaves Rs 100 + 18% GST. */
    public static final Map<Integer, BigDecimal> CHEQUE_FEE = Map.of(25, new BigDecimal("0.00"), 50, new BigDecimal("118.00"));
    public static final int CHEQUE_ETA_DAYS = 7;

    private final ObjectMapper mapper;
    private final Clock clock;
    private final Resource seed;

    private LocalDate today;
    private Map<String, Customer> customers;
    private Map<String, Account> accounts;
    private Map<String, ServiceRequest> serviceRequests;
    private Map<String, String> idempotency;
    private int srSequence;

    public BankStore(ObjectMapper mapper, Clock clock,
                     @Value("${bank.seed:classpath:io/lakeside/mockbank/seed.json}") Resource seed) {
        this.mapper = mapper;
        this.clock = clock;
        this.seed = seed;
        reset();
    }

    /** Reloads the seed data, discarding any requests created since start-up. */
    public synchronized void reset() {
        Seed data;
        try (InputStream in = seed.getInputStream()) {
            data = mapper.readValue(in, Seed.class);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read seed data", e);
        }
        today = LocalDate.now(clock);
        customers = new LinkedHashMap<>();
        accounts = new LinkedHashMap<>();
        serviceRequests = new LinkedHashMap<>();
        idempotency = new HashMap<>();
        srSequence = 700300;

        for (Seed.SeedCustomer c : data.customers()) {
            customers.put(c.customerId(), new Customer(c.customerId(), c.displayName(), c.registeredAddress()));
            for (Seed.SeedAccount a : c.accounts()) {
                accounts.put(a.accountId(), toAccount(c.customerId(), a));
            }
            if (c.openServiceRequests() != null) {
                for (Seed.SeedServiceRequest sr : c.openServiceRequests()) {
                    OffsetDateTime created = at(sr.daysAgo(), 0);
                    serviceRequests.put(sr.srId(), new ServiceRequest(sr.srId(), c.customerId(), sr.type(), sr.accountId(),
                            sr.leaves(), sr.status(), created, created.toLocalDate().plusDays(sr.etaDaysFromCreation()),
                            CHEQUE_FEE.get(sr.leaves())));
                }
            }
        }
    }

    private Account toAccount(String customerId, Seed.SeedAccount a) {
        List<Seed.SeedTransaction> ordered = a.transactions().stream()
                .sorted(Comparator.comparingInt(Seed.SeedTransaction::daysAgo).reversed())
                .toList();
        List<Transaction> txns = new ArrayList<>();
        BigDecimal running = a.openingBalance();
        for (int i = 0; i < ordered.size(); i++) {
            Seed.SeedTransaction t = ordered.get(i);
            String status = t.status() == null ? "posted" : t.status();
            BigDecimal balanceAfter = null;
            if ("posted".equals(status)) {
                running = "debit".equals(t.direction()) ? running.subtract(t.amount()) : running.add(t.amount());
                balanceAfter = running;
            }
            txns.add(new Transaction(t.id(), a.accountId(), at(t.daysAgo(), i), t.direction(), t.amount(), t.descriptor(),
                    t.channel(), t.merchant(), t.explanation(), status, Boolean.TRUE.equals(t.recurring()),
                    t.mandateRef(), t.location(), balanceAfter));
        }
        return new Account(a.accountId(), customerId, a.accountNumber(), a.type(), a.product(), a.currency(),
                a.openingBalance(), Boolean.TRUE.equals(a.chequeBookEligible()), a.chequeBookIneligibleReason(),
                List.copyOf(txns));
    }

    private OffsetDateTime at(int daysAgo, int minuteOffset) {
        return today.minusDays(daysAgo).atTime(10, 0).atZone(clock.getZone()).toOffsetDateTime().plusMinutes(minuteOffset);
    }

    // ---------------- queries ----------------

    public synchronized LocalDate today() {
        return today;
    }

    public synchronized List<Customer> customers() {
        return List.copyOf(customers.values());
    }

    public synchronized Optional<Customer> customer(String customerId) {
        return Optional.ofNullable(customers.get(customerId));
    }

    public synchronized List<Account> accountsFor(String customerId) {
        return accounts.values().stream().filter(a -> a.customerId().equals(customerId)).toList();
    }

    public synchronized Optional<Account> accountFor(String customerId, String accountId) {
        return Optional.ofNullable(accounts.get(accountId)).filter(a -> a.customerId().equals(customerId));
    }

    public synchronized Optional<Transaction> transactionFor(String customerId, String transactionId) {
        return accountsFor(customerId).stream()
                .flatMap(a -> a.transactions().stream())
                .filter(t -> t.id().equals(transactionId))
                .findFirst();
    }

    public synchronized List<Transaction> search(String customerId, TransactionQuery q) {
        Stream<Account> scope = q.accountId() == null
                ? accountsFor(customerId).stream()
                : accountFor(customerId, q.accountId()).stream();
        List<String> terms = q.text() == null ? List.of()
                : Stream.of(q.text().toLowerCase(Locale.ROOT).split("\\s+")).filter(s -> !s.isBlank()).toList();
        return scope.flatMap(a -> a.transactions().stream())
                .filter(t -> q.direction() == null || t.direction().equals(q.direction()))
                .filter(t -> q.minAmount() == null || t.amount().compareTo(q.minAmount()) >= 0)
                .filter(t -> q.maxAmount() == null || t.amount().compareTo(q.maxAmount()) <= 0)
                .filter(t -> q.dateFrom() == null || !t.postedAt().toLocalDate().isBefore(q.dateFrom()))
                .filter(t -> q.dateTo() == null || !t.postedAt().toLocalDate().isAfter(q.dateTo()))
                .filter(t -> terms.isEmpty() || matchesAny(t, terms))
                .sorted(Comparator.comparing(Transaction::postedAt).reversed())
                .limit(q.limit())
                .toList();
    }

    private static boolean matchesAny(Transaction t, List<String> terms) {
        String haystack = Stream.concat(Stream.of(t.descriptor(), t.channel()),
                        t.merchant().values().stream().filter(Objects::nonNull).map(Object::toString))
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
        return terms.stream().anyMatch(haystack::contains);
    }

    public synchronized List<ServiceRequest> serviceRequestsFor(String customerId, String type) {
        return serviceRequests.values().stream()
                .filter(s -> s.customerId().equals(customerId) && (type == null || s.type().equals(type)))
                .sorted(Comparator.comparing(ServiceRequest::createdAt).reversed())
                .toList();
    }

    public synchronized Optional<ServiceRequest> openChequeBookRequest(String customerId, String accountId) {
        return serviceRequestsFor(customerId, ServiceRequest.CHEQUE_BOOK).stream()
                .filter(s -> s.accountId().equals(accountId) && s.isOpen())
                .findFirst();
    }

    // ---------------- commands ----------------

    public record CreateResult(ServiceRequest request, boolean replayed) {
    }

    /**
     * Atomically: replay by idempotency key, otherwise reject if an open request exists,
     * otherwise create. Returns empty when an open request blocks creation.
     */
    public synchronized Optional<CreateResult> createChequeBookRequest(String customerId, String accountId, int leaves,
                                                                       String idempotencyKey) {
        String existingId = idempotency.get(customerId + "|" + idempotencyKey);
        if (existingId != null) {
            return Optional.of(new CreateResult(serviceRequests.get(existingId), true));
        }
        if (openChequeBookRequest(customerId, accountId).isPresent()) {
            return Optional.empty();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        String id = "SR-" + (++srSequence);
        ServiceRequest sr = new ServiceRequest(id, customerId, ServiceRequest.CHEQUE_BOOK, accountId, leaves, "Submitted",
                now, now.toLocalDate().plusDays(CHEQUE_ETA_DAYS), CHEQUE_FEE.get(leaves));
        serviceRequests.put(id, sr);
        idempotency.put(customerId + "|" + idempotencyKey, id);
        return Optional.of(new CreateResult(sr, false));
    }
}
