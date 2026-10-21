package io.lakeside.assistant.web;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;

import io.lakeside.assistant.bank.BankApiError;
import io.lakeside.assistant.bank.BankClient;
import io.lakeside.assistant.bank.BankUnavailableException;
import io.lakeside.assistant.config.AssistantProperties;
import io.lakeside.assistant.session.AccountRef;
import io.lakeside.assistant.session.ConversationSession;
import io.lakeside.assistant.session.SessionStore;
import io.lakeside.assistant.web.Dtos.LoginRequest;
import io.lakeside.assistant.web.Dtos.LoginResponse;

import jakarta.validation.Valid;

/**
 * Simulated upstream authentication. In production the session would be minted from the
 * bank's identity-provider token (OIDC) after the customer signs in to the app; these
 * endpoints stand in for that hand-off and are disabled with {@code DEMO_LOGIN_ENABLED=false}.
 */
@RestController
class SessionController {

    private final BankClient bank;
    private final SessionStore sessions;
    private final AssistantProperties props;

    SessionController(BankClient bank, SessionStore sessions, AssistantProperties props) {
        this.bank = bank;
        this.sessions = sessions;
        this.props = props;
    }

    @GetMapping("/api/demo/customers")
    Map<String, JsonNode> demoCustomers() {
        requireDemo();
        try {
            return Map.of("customers", bank.demoCustomers());
        } catch (BankApiError | BankUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "banking service unavailable");
        }
    }

    @PostMapping("/api/session")
    LoginResponse login(@Valid @RequestBody LoginRequest body) {
        requireDemo();
        try {
            JsonNode customer = bank.customer(body.customerId());
            JsonNode accounts = bank.accounts(body.customerId());
            ConversationSession s = sessions.create(body.customerId(), customer.path("display_name").asText(),
                    AccountRef.listFrom(accounts));
            return new LoginResponse(s.token(), s.displayName(), sessions.ttl().toSeconds());
        } catch (BankApiError e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown customer");
        } catch (BankUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "banking service unavailable");
        }
    }

    @DeleteMapping("/api/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        sessions.delete(BearerAuth.token(authorization));
    }

    private void requireDemo() {
        if (!props.demoLoginEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
        }
    }
}
