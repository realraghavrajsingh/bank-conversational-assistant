package io.lakeside.assistant.support;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.lakeside.assistant.agent.Agent;
import io.lakeside.assistant.bank.BankClient;
import io.lakeside.assistant.llm.LlmClient;
import io.lakeside.assistant.session.AccountRef;
import io.lakeside.assistant.session.ConversationSession;
import io.lakeside.assistant.session.SessionStore;
import io.lakeside.assistant.tools.BankingTools;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/** Hand-wired components (no Spring context) for fast orchestrator tests. */
public final class Fixtures {

    public static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build();
    public static final Clock CLOCK = Clock.system(ZoneId.of("Asia/Kolkata"));
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private Fixtures() {
    }

    public static BankClient bank() {
        RestClient http = RestClient.builder()
                .baseUrl(MockBankServer.baseUrl())
                .defaultHeader(BankClient.SERVICE_KEY_HEADER, MockBankServer.SERVICE_KEY)
                .build();
        return new BankClient(http, MAPPER);
    }

    public static BankingTools tools() {
        return new BankingTools(MAPPER, VALIDATOR, bank(), Duration.ofMinutes(10), CLOCK);
    }

    public static Agent agent(LlmClient llm) {
        return agent(llm, 6);
    }

    public static Agent agent(LlmClient llm, int maxSteps) {
        return new Agent(llm, tools(), MAPPER, maxSteps, 40, CLOCK);
    }

    public static ConversationSession session(String customerId) {
        BankClient bank = bank();
        String name = bank.customer(customerId).path("display_name").asText();
        return new SessionStore(Duration.ofMinutes(30), CLOCK)
                .create(customerId, name, AccountRef.listFrom(bank.accounts(customerId)));
    }
}
