package io.lakeside.assistant.tools;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.lakeside.assistant.bank.BankApiError;
import io.lakeside.assistant.bank.BankClient;
import io.lakeside.assistant.bank.BankUnavailableException;
import io.lakeside.assistant.llm.ToolSpec;
import io.lakeside.assistant.session.AccountRef;
import io.lakeside.assistant.session.ConversationSession;
import io.lakeside.assistant.session.PendingAction;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

/**
 * The banking capabilities exposed to the model as tools.
 *
 * <ul>
 *   <li>The model never supplies the customer identity; it comes from the session.</li>
 *   <li>Account ids are checked against the session's own accounts before any call.</li>
 *   <li>Arguments are deserialised into typed records and validated; problems go back to the
 *       model as structured errors so it can correct itself.</li>
 *   <li>The only write (cheque book) is two-phase, and the confirmation step is enforced here,
 *       not just requested in the prompt.</li>
 * </ul>
 */
public class BankingTools {

    @FunctionalInterface
    private interface Handler<A> {
        JsonNode run(ConversationSession session, A args);
    }

    private record Tool<A>(String name, String description, Class<A> argType, ObjectNode schema, Handler<A> handler) {
    }

    private final ObjectMapper mapper;
    private final Validator validator;
    private final BankClient bank;
    private final Duration pendingTtl;
    private final Clock clock;
    private final Map<String, Tool<?>> tools = new LinkedHashMap<>();
    private final List<ToolSpec> specs;

    public BankingTools(ObjectMapper mapper, Validator validator, BankClient bank, Duration pendingTtl, Clock clock) {
        this.mapper = mapper;
        this.validator = validator;
        this.bank = bank;
        this.pendingTtl = pendingTtl;
        this.clock = clock;

        register("list_accounts",
                "List the customer's accounts (id, type, masked number). Use it to work out which account they mean.",
                ToolArgs.NoArgs.class, """
                        {"type":"object","properties":{}}""",
                this::listAccounts);
        register("get_balance",
                "Get the current ledger and available balance of one of the customer's accounts. "
                        + "Available balance excludes pending card holds.",
                ToolArgs.Balance.class, """
                        {"type":"object","properties":{
                          "account_id":{"type":"string","description":"account_id of one of the customer's accounts"}},
                         "required":["account_id"]}""",
                this::getBalance);
        register("search_transactions",
                "Search the customer's transactions, e.g. for 'what was this debit', 'what did I pay Uber', "
                        + "'charges last week'. Returns summaries plus count and total_amount; use total_amount for "
                        + "totals instead of adding numbers yourself.",
                ToolArgs.SearchTransactions.class, """
                        {"type":"object","properties":{
                          "account_id":{"type":"string","description":"Restrict to one account; omit to search all of the customer's accounts"},
                          "direction":{"type":"string","enum":["debit","credit"],"description":"debit = money out (default), credit = money in"},
                          "query":{"type":"string","description":"Merchant, payee, statement text or category keywords, e.g. 'amazon', 'uber', 'emi', 'fee'"},
                          "min_amount":{"type":"number","minimum":0},
                          "max_amount":{"type":"number","minimum":0},
                          "date_from":{"type":"string","description":"Inclusive start date, YYYY-MM-DD"},
                          "date_to":{"type":"string","description":"Inclusive end date, YYYY-MM-DD"},
                          "limit":{"type":"integer","minimum":1,"maximum":25,"description":"Default 10"}}}""",
                this::searchTransactions);
        register("get_transaction_details",
                "Get full details of one transaction: decoded merchant, channel, plain-language explanation, "
                        + "recurring / mandate information and dispute eligibility.",
                ToolArgs.TransactionDetails.class, """
                        {"type":"object","properties":{
                          "transaction_id":{"type":"string","description":"e.g. TXN-90012, from search_transactions"}},
                         "required":["transaction_id"]}""",
                this::transactionDetails);
        register("prepare_cheque_book_request",
                "Step 1 of ordering a cheque book: checks eligibility and existing requests and returns a summary "
                        + "(fee, masked delivery address, delivery time) plus a pending_request_id. Does NOT place the order.",
                ToolArgs.PrepareChequeBook.class, """
                        {"type":"object","properties":{
                          "account_id":{"type":"string"},
                          "leaves":{"type":"integer","description":"Number of cheque leaves, default 25; options are returned"}},
                         "required":["account_id"]}""",
                this::prepareChequeBook);
        register("submit_cheque_book_request",
                "Step 2: places the cheque book order for a pending_request_id. Only call it after the customer has "
                        + "explicitly confirmed the summary in a later message.",
                ToolArgs.SubmitChequeBook.class, """
                        {"type":"object","properties":{
                          "pending_request_id":{"type":"string","description":"From prepare_cheque_book_request"}},
                         "required":["pending_request_id"]}""",
                this::submitChequeBook);
        register("get_service_requests",
                "List the customer's service requests (cheque book orders) and their status.",
                ToolArgs.ServiceRequests.class, """
                        {"type":"object","properties":{
                          "type":{"type":"string","enum":["cheque_book"]}}}""",
                this::serviceRequests);

        this.specs = tools.values().stream().map(t -> new ToolSpec(t.name(), t.description(), t.schema())).toList();
    }

    private <A> void register(String name, String description, Class<A> argType, String schemaJson, Handler<A> handler) {
        try {
            tools.put(name, new Tool<>(name, description, argType, (ObjectNode) mapper.readTree(schemaJson), handler));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid schema for tool " + name, e);
        }
    }

    public List<ToolSpec> specs() {
        return specs;
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /** Runs a tool. Never throws for problems the model caused; they become error results. */
    public ToolOutcome execute(String name, JsonNode rawArgs, ToolContext ctx) {
        Tool<?> tool = tools.get(name);
        if (tool == null) {
            return failure("unknown_tool", "No tool named " + name);
        }
        return run(tool, rawArgs, ctx.session());
    }

    private <A> ToolOutcome run(Tool<A> tool, JsonNode rawArgs, ConversationSession session) {
        A args;
        try {
            JsonNode input = rawArgs == null || rawArgs.isNull() ? mapper.createObjectNode() : rawArgs;
            args = mapper.treeToValue(input, tool.argType());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return failure("invalid_arguments", describe(e));
        }
        Set<ConstraintViolation<A>> violations = validator.validate(args);
        if (!violations.isEmpty()) {
            return failure("invalid_arguments", violations.stream()
                    .map(v -> snake(v.getPropertyPath().toString()) + ": " + v.getMessage())
                    .distinct().sorted().collect(Collectors.joining("; ")));
        }
        try {
            return new ToolOutcome(tool.handler().run(session, args), true, null);
        } catch (ToolFailure f) {
            return failure(f.code(), f.getMessage());
        } catch (BankApiError e) {
            return switch (e.status()) {
                case 404 -> failure("not_found", "No matching record for this customer.");
                case 409 -> {
                    ObjectNode body = mapper.createObjectNode().put("error", "conflict").put("message", e.bankMessage());
                    if (e.detail() != null) body.set("detail", e.detail());
                    yield new ToolOutcome(body, false, "conflict");
                }
                default -> failure("rejected", e.bankMessage());
            };
        } catch (BankUnavailableException e) {
            return failure("bank_unavailable", "Banking systems are temporarily unavailable. Do not guess any figures; "
                    + "apologise and suggest trying again shortly.");
        }
    }

    // ---------------- handlers ----------------

    private JsonNode listAccounts(ConversationSession s, ToolArgs.NoArgs a) {
        JsonNode accounts = bank.accounts(s.customerId());
        s.accounts(AccountRef.listFrom(accounts));
        return mapper.createObjectNode().set("accounts", accounts);
    }

    private JsonNode getBalance(ConversationSession s, ToolArgs.Balance a) {
        requireOwnAccount(s, a.accountId());
        return bank.balance(s.customerId(), a.accountId());
    }

    private JsonNode searchTransactions(ConversationSession s, ToolArgs.SearchTransactions a) {
        requireOwnAccount(s, a.accountId());
        JsonNode result = bank.searchTransactions(s.customerId(), new BankClient.TransactionSearch(
                a.accountId(), a.direction() == null ? "debit" : a.direction(), a.query(), a.minAmount(), a.maxAmount(),
                a.dateFrom(), a.dateTo(), a.limit() == null ? 10 : a.limit()));
        if (result.path("count").asInt() == 0 && result instanceof ObjectNode o) {
            o.put("hint", "No matches. Consider widening the date range, dropping the query or the direction, "
                    + "or asking the customer for more detail.");
        }
        return result;
    }

    private JsonNode transactionDetails(ConversationSession s, ToolArgs.TransactionDetails a) {
        return bank.transaction(s.customerId(), a.transactionId());
    }

    private JsonNode prepareChequeBook(ConversationSession s, ToolArgs.PrepareChequeBook a) {
        requireOwnAccount(s, a.accountId());
        JsonNode elig = bank.chequeBookEligibility(s.customerId(), a.accountId());
        if (!elig.path("eligible").asBoolean()) {
            ObjectNode out = mapper.createObjectNode().put("eligible", false);
            out.set("reason", elig.get("ineligible_reason"));
            out.set("existing_open_request", elig.get("existing_open_request"));
            return out;
        }
        Map<Integer, String> fees = new LinkedHashMap<>();
        elig.path("leaf_options").forEach(o -> fees.put(o.path("leaves").asInt(), o.path("fee").asText()));
        int leaves = a.leaves() == null ? 25 : a.leaves();
        if (!fees.containsKey(leaves)) {
            throw new ToolFailure("invalid_leaves", "leaves must be one of " + new ArrayList<>(fees.keySet()));
        }
        PendingAction pa = s.newPendingChequeBook(a.accountId(), leaves, clock.instant());
        ObjectNode out = mapper.createObjectNode().put("eligible", true).put("pending_request_id", pa.id());
        out.putObject("summary")
                .put("account", elig.path("masked_number").asText())
                .put("leaves", leaves)
                .put("fee", fees.get(leaves))
                .put("delivery_address", elig.path("delivery_address_masked").asText())
                .put("delivery_eta_days", elig.path("delivery_eta_days").asInt());
        out.set("leaf_options", elig.get("leaf_options"));
        out.put("next_step", "Show this summary and ask the customer to confirm. Call submit_cheque_book_request "
                + "only after they confirm in a NEW message.");
        return out;
    }

    private JsonNode submitChequeBook(ConversationSession s, ToolArgs.SubmitChequeBook a) {
        PendingAction pa = s.pending(a.pendingRequestId());
        if (pa == null) {
            throw new ToolFailure("unknown_pending_request", "No such pending request. Call prepare_cheque_book_request first.");
        }
        if (pa.createdTurn() >= s.turn()) {
            // Server-side enforcement of explicit confirmation: the customer must have replied after
            // seeing the summary. Blocks propose-and-execute in one step, including via prompt
            // injection hidden in transaction text.
            throw new ToolFailure("confirmation_required",
                    "The customer has not confirmed yet. Present the summary and wait for their reply.");
        }
        Instant now = clock.instant();
        if (Duration.between(pa.createdAt(), now).compareTo(pendingTtl) > 0) {
            s.removePending(pa.id());
            throw new ToolFailure("pending_request_expired", "The pending request expired. Prepare it again.");
        }
        // The pending id is the idempotency key, so a retry can never place a second order.
        JsonNode sr = bank.createChequeBookRequest(s.customerId(), pa.accountId(), pa.leaves(), pa.id());
        pa.markConsumed();
        ObjectNode out = mapper.createObjectNode().put("status", "submitted");
        out.set("service_request", sr);
        return out;
    }

    private JsonNode serviceRequests(ConversationSession s, ToolArgs.ServiceRequests a) {
        return mapper.createObjectNode().set("service_requests",
                bank.serviceRequests(s.customerId(), a.type() == null ? "cheque_book" : a.type()));
    }

    // ---------------- helpers ----------------

    private static void requireOwnAccount(ConversationSession s, String accountId) {
        if (accountId != null && !s.accountIds().contains(accountId)) {
            throw new ToolFailure("unknown_account", "That account does not belong to this customer. Use list_accounts.");
        }
    }

    private ToolOutcome failure(String code, String message) {
        return new ToolOutcome(mapper.createObjectNode().put("error", code).put("message", message), false, code);
    }

    private static String describe(Exception e) {
        if (e instanceof InvalidFormatException ife) {
            return path(ife) + ": invalid value '" + ife.getValue() + "' (expected " + expected(ife.getTargetType()) + ")";
        }
        if (e instanceof MismatchedInputException mie) {
            return path(mie) + ": " + mie.getOriginalMessage();
        }
        return "could not read arguments";
    }

    private static String path(MismatchedInputException e) {
        String p = e.getPath().stream().map(r -> r.getFieldName() == null ? "[" + r.getIndex() + "]" : r.getFieldName())
                .collect(Collectors.joining("."));
        return p.isEmpty() ? "arguments" : p;
    }

    private static String expected(Class<?> type) {
        if (type == null) return "valid value";
        return switch (type.getSimpleName()) {
            case "LocalDate" -> "date YYYY-MM-DD";
            case "Integer", "int" -> "integer";
            case "BigDecimal" -> "number";
            default -> type.getSimpleName();
        };
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
