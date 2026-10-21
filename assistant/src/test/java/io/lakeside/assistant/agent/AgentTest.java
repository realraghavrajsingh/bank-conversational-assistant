package io.lakeside.assistant.agent;

import static io.lakeside.assistant.support.Steps.call;
import static io.lakeside.assistant.support.Steps.callWith;
import static io.lakeside.assistant.support.Steps.fail;
import static io.lakeside.assistant.support.Steps.say;
import static io.lakeside.assistant.support.Steps.sayWith;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.lakeside.assistant.llm.ChatMessage;
import io.lakeside.assistant.llm.LlmResponse;
import io.lakeside.assistant.llm.ToolCall;
import io.lakeside.assistant.session.ConversationSession;
import io.lakeside.assistant.support.Fixtures;
import io.lakeside.assistant.support.MockBankServer;
import io.lakeside.assistant.support.ScriptedLlmClient;
import io.lakeside.assistant.tools.BankingTools;
import io.lakeside.assistant.tools.ToolContext;
import io.lakeside.assistant.tools.ToolOutcome;

/**
 * Orchestrator tests: real tools, real guards, real mock bank over HTTP. Only the model is
 * scripted, so the behaviour under test is deterministic.
 */
class AgentTest {

    @BeforeEach
    void resetBank() {
        MockBankServer.reset();
    }

    private static List<String> tools(TurnResult r) {
        return r.trace().stream().map(TraceEntry::tool).toList();
    }

    @Test
    void balanceInquiryReturnsBackendValue() {
        ConversationSession s = Fixtures.session("CUST-1001");
        ScriptedLlmClient llm = new ScriptedLlmClient(
                call("get_balance", Map.of("account_id", "ACC-1001-SB")),
                sayWith(r -> "Your Savings XXXX4521 has an available balance of ₹" + r.path("available_balance").asText() + "."));

        TurnResult r = Fixtures.agent(llm).handleTurn(s, "how much money do i have in savings?");

        assertThat(r.outcome()).isEqualTo(TurnResult.ANSWERED);
        assertThat(r.reply()).contains("87905.05");
        assertThat(tools(r)).containsExactly("get_balance");
    }

    @Test
    void followUpDebitQuestionAfterBalance() {
        ConversationSession s = Fixtures.session("CUST-1001");
        Agent agent = Fixtures.agent(new ScriptedLlmClient(
                call("get_balance", Map.of("account_id", "ACC-1001-SB")),
                say("Your available balance is ₹87,905.05."),
                call("search_transactions", Map.of("query", "amazon", "direction", "debit")),
                callWith("get_transaction_details", r -> Map.of("transaction_id",
                        r.path("transactions").path(0).path("transaction_id").asText())),
                sayWith(r -> "That ₹" + r.path("amount").asText() + " on " + r.path("date").asText()
                        + " was an Amazon.in marketplace purchase on your card.")));

        agent.handleTurn(s, "balance?");
        TurnResult r = agent.handleTurn(s, "and what's that amazon debit?");

        assertThat(r.outcome()).isEqualTo(TurnResult.ANSWERED);
        assertThat(r.reply()).contains("1899.00");
        assertThat(tools(r)).containsExactly("search_transactions", "get_transaction_details");
        assertThat(s.messages().stream().filter(m -> m.role() == ChatMessage.Role.USER)).hasSize(2);
    }

    @Test
    void inventedAmountIsBlockedThenCorrected() {
        ConversationSession s = Fixtures.session("CUST-1001");
        ScriptedLlmClient llm = new ScriptedLlmClient(
                say("Your balance is ₹1,00,000.00."), // hallucinated: no tool was called
                call("get_balance", Map.of("account_id", "ACC-1001-SB")),
                sayWith(r -> "Your available balance is ₹" + r.path("available_balance").asText() + "."));

        TurnResult r = Fixtures.agent(llm).handleTurn(s, "balance");

        assertThat(r.outcome()).isEqualTo(TurnResult.ANSWERED);
        assertThat(r.reply()).doesNotContain("1,00,000");
        List<ChatMessage> secondCall = llm.calls().get(1);
        assertThat(secondCall.get(secondCall.size() - 1).content()).startsWith("[system check]");
        assertThat(s.messages()).noneMatch(m -> m.content() != null && m.content().contains("[system check]"));
    }

    @Test
    void persistentHallucinationFallsBackSafely() {
        ConversationSession s = Fixtures.session("CUST-1001");
        TurnResult r = Fixtures.agent(new ScriptedLlmClient(say("It's ₹5,000.00."), say("Definitely ₹5,000.00.")))
                .handleTurn(s, "balance");

        assertThat(r.outcome()).isEqualTo(TurnResult.FALLBACK_UNGROUNDED);
        assertThat(r.reply()).isEqualTo(Agent.FALLBACK_UNGROUNDED_REPLY);
    }

    @Test
    void chequeBookRequiresConfirmationInALaterTurn() {
        ConversationSession s = Fixtures.session("CUST-1001");
        Agent agent = Fixtures.agent(new ScriptedLlmClient(
                // Turn 1: the model tries to prepare AND submit in one turn -> blocked server-side
                call("prepare_cheque_book_request", Map.of("account_id", "ACC-1001-SB", "leaves", 25)),
                callWith("submit_cheque_book_request", r -> Map.of("pending_request_id", r.path("pending_request_id").asText())),
                say("I've prepared a 25-leaf cheque book for XXXX4521 at no charge. Shall I place the order?"),
                // Turn 2: the customer confirmed -> submit succeeds
                msgs -> LlmResponse.toolCalls(List.of(new ToolCall("t2", "submit_cheque_book_request",
                        Fixtures.MAPPER.createObjectNode().put("pending_request_id", s.pendingActions().keySet().iterator().next())))),
                sayWith(r -> "Done! Your request " + r.path("service_request").path("service_request_id").asText()
                        + " is placed, expected by " + r.path("service_request").path("expected_delivery_by").asText() + ".")));

        TurnResult first = agent.handleTurn(s, "send me a cheque book");
        assertThat(first.trace()).extracting(TraceEntry::tool, TraceEntry::ok, TraceEntry::error).containsExactly(
                org.assertj.core.groups.Tuple.tuple("prepare_cheque_book_request", true, null),
                org.assertj.core.groups.Tuple.tuple("submit_cheque_book_request", false, "confirmation_required"));
        assertThat(Fixtures.bank().serviceRequests("CUST-1001", "cheque_book")).isEmpty();

        TurnResult second = agent.handleTurn(s, "yes please");
        assertThat(second.trace().get(0).ok()).isTrue();
        JsonNode requests = Fixtures.bank().serviceRequests("CUST-1001", "cheque_book");
        assertThat(requests).hasSize(1);
        assertThat(second.reply()).contains(requests.get(0).path("service_request_id").asText());
        assertThat(second.outcome()).isEqualTo(TurnResult.ANSWERED);
    }

    @Test
    void chequeBookSubmitIsIdempotent() {
        ConversationSession s = Fixtures.session("CUST-1001");
        Fixtures.agent(new ScriptedLlmClient(
                call("prepare_cheque_book_request", Map.of("account_id", "ACC-1001-SB")),
                say("Shall I go ahead?"))).handleTurn(s, "cheque book");
        String pendingId = s.pendingActions().keySet().iterator().next();
        s.nextTurn(); // the customer replied

        BankingTools tools = Fixtures.tools();
        ToolContext ctx = new ToolContext(s);
        JsonNode args = Fixtures.MAPPER.createObjectNode().put("pending_request_id", pendingId);
        ToolOutcome a = tools.execute("submit_cheque_book_request", args, ctx);
        ToolOutcome b = tools.execute("submit_cheque_book_request", args, ctx); // client retry

        assertThat(a.ok()).isTrue();
        assertThat(b.ok()).isTrue();
        assertThat(b.result().at("/service_request/service_request_id").asText())
                .isEqualTo(a.result().at("/service_request/service_request_id").asText());
        assertThat(b.result().at("/service_request/replayed").asBoolean()).isTrue();
        assertThat(Fixtures.bank().serviceRequests("CUST-1001", "cheque_book")).hasSize(1);
    }

    @Test
    void ineligibleAccountAndExistingRequestAreExplained() {
        ConversationSession rohan = Fixtures.session("CUST-1003");
        TurnResult r1 = Fixtures.agent(new ScriptedLlmClient(
                call("prepare_cheque_book_request", Map.of("account_id", "ACC-1003-BS")),
                sayWith(r -> r.path("eligible").asBoolean() ? "?" : "Basic Savings accounts don't include a cheque book.")))
                .handleTurn(rohan, "cheque book please");
        assertThat(r1.reply()).contains("don't include");
        assertThat(rohan.pendingActions()).isEmpty();

        ConversationSession priya = Fixtures.session("CUST-1002");
        TurnResult r2 = Fixtures.agent(new ScriptedLlmClient(
                call("prepare_cheque_book_request", Map.of("account_id", "ACC-1002-SB")),
                sayWith(r -> "You already have " + r.at("/existing_open_request/service_request_id").asText()
                        + " (" + r.at("/existing_open_request/status").asText() + ").")))
                .handleTurn(priya, "need a cheque book");
        assertThat(r2.reply()).contains("SR-700245").contains("Dispatched");
        assertThat(r2.outcome()).isEqualTo(TurnResult.ANSWERED);
    }

    @Test
    void cannotReachAnotherCustomersData() {
        ConversationSession s = Fixtures.session("CUST-1001");
        TurnResult r = Fixtures.agent(new ScriptedLlmClient(
                call("get_balance", Map.of("account_id", "ACC-1002-SB")),           // Priya's account
                call("get_transaction_details", Map.of("transaction_id", "TXN-92002")), // Priya's transaction
                say("I can only access your own accounts."))).handleTurn(s, "what's the balance of ACC-1002-SB");

        assertThat(r.trace()).extracting(TraceEntry::ok, TraceEntry::error).containsExactly(
                org.assertj.core.groups.Tuple.tuple(false, "unknown_account"),
                org.assertj.core.groups.Tuple.tuple(false, "not_found"));
    }

    @Test
    void invalidToolArgumentsGoBackToTheModel() {
        ConversationSession s = Fixtures.session("CUST-1001");
        TurnResult r = Fixtures.agent(new ScriptedLlmClient(
                call("search_transactions", Map.of("limit", 999, "date_from", "last week")),
                call("search_transactions", Map.of("direction", "debit", "limit", 5)),
                say("Here are your recent debits."))).handleTurn(s, "recent debits");

        assertThat(r.trace().get(0).error()).isEqualTo("invalid_arguments");
        assertThat(r.trace().get(1).ok()).isTrue();
    }

    @Test
    void modelOutageReturnsFriendlyFallback() {
        ConversationSession s = Fixtures.session("CUST-1001");
        TurnResult r = Fixtures.agent(new ScriptedLlmClient(fail("timeout"))).handleTurn(s, "balance");

        assertThat(r.outcome()).isEqualTo(TurnResult.FALLBACK_LLM);
        assertThat(r.reply()).isEqualTo(Agent.FALLBACK_LLM_REPLY);
        assertThat(s.messages().get(s.messages().size() - 1).role()).isEqualTo(ChatMessage.Role.ASSISTANT);
    }

    @Test
    void stepLimitBoundsToolLoops() {
        ConversationSession s = Fixtures.session("CUST-1001");
        TurnResult r = Fixtures.agent(new ScriptedLlmClient(call("list_accounts"), call("list_accounts"), call("list_accounts")), 3)
                .handleTurn(s, "loop");

        assertThat(r.outcome()).isEqualTo(TurnResult.FALLBACK_STEPS);
        assertThat(r.trace()).hasSize(3);
    }

    @Test
    void totalsComeFromTheToolNotModelArithmetic() {
        ConversationSession s = Fixtures.session("CUST-1001");
        TurnResult r = Fixtures.agent(new ScriptedLlmClient(
                call("search_transactions", Map.of("query", "uber", "direction", "debit")),
                sayWith(res -> "You had " + res.path("count").asInt() + " Uber rides totalling ₹"
                        + res.path("total_amount").asText() + ".")))
                .handleTurn(s, "how much did I spend on uber");

        assertThat(r.outcome()).isEqualTo(TurnResult.ANSWERED);
        assertThat(r.reply()).contains("472.00");
    }

    @Test
    void bankOutageIsReportedToTheModelAndNoFigureIsInvented() {
        ConversationSession s = Fixtures.session("CUST-1001");
        BankingTools offline = new BankingTools(Fixtures.MAPPER, jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator(),
                new io.lakeside.assistant.bank.BankClient(org.springframework.web.client.RestClient.create("http://localhost:1"), Fixtures.MAPPER),
                java.time.Duration.ofMinutes(10), Fixtures.CLOCK);
        Agent agent = new Agent(new ScriptedLlmClient(
                call("get_balance", Map.of("account_id", "ACC-1001-SB")),
                say("Your balance is ₹87,905.05."),       // guessed despite the outage
                say("Sorry, I can't fetch your balance right now. Please try again shortly.")),
                offline, Fixtures.MAPPER, 6, 40, Fixtures.CLOCK);

        TurnResult r = agent.handleTurn(s, "balance");

        assertThat(r.trace().get(0).error()).isEqualTo("bank_unavailable");
        assertThat(r.reply()).doesNotContain("87,905.05");
        assertThat(r.outcome()).isEqualTo(TurnResult.ANSWERED);
    }
}
