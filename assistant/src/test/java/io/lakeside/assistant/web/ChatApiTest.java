package io.lakeside.assistant.web;

import static io.lakeside.assistant.support.Steps.call;
import static io.lakeside.assistant.support.Steps.sayWith;
import static io.lakeside.assistant.web.ApiTestSupport.chat;
import static io.lakeside.assistant.web.ApiTestSupport.login;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import io.lakeside.assistant.support.MockBankServer;
import io.lakeside.assistant.support.ScriptedLlmClient;

/** The full HTTP API with the real Spring wiring; only the model is scripted. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiTestSupport.ScriptedModel.class)
class ChatApiTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        ApiTestSupport.bankProperties(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ScriptedLlmClient llm;

    @BeforeEach
    void reset() {
        MockBankServer.reset();
    }

    @Test
    void endToEndChatOverHttp() throws Exception {
        llm.script(
                call("get_balance", Map.of("account_id", "ACC-1001-SB")),
                sayWith(r -> "Your available balance is ₹" + r.path("available_balance").asText() + "."));

        mvc.perform(chat("not-a-token", "hi")).andExpect(status().isUnauthorized());
        String token = login(mvc, "CUST-1001");

        mvc.perform(chat(token, "what's my balance"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.reply", containsString("87905.05")))
                .andExpect(jsonPath("$.outcome").value("answered"))
                .andExpect(jsonPath("$.turn").value(1))
                .andExpect(jsonPath("$.trace[0].tool").value("get_balance"))
                .andExpect(jsonPath("$.trace[0].ok").value(true));
    }

    @Test
    void sessionLifecycle() throws Exception {
        mvc.perform(get("/api/demo/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customers[0].customer_id").value("CUST-1001"));
        mvc.perform(post("/api/session").contentType(MediaType.APPLICATION_JSON).content("{\"customer_id\":\"CUST-1001\"}"))
                .andExpect(jsonPath("$.display_name").value("Aarav Mehta"))
                .andExpect(jsonPath("$.expires_in").value(1800));

        String token = login(mvc, "CUST-1002");
        mvc.perform(delete("/api/session").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mvc.perform(chat(token, "hi")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsBadLoginAndBlankMessages() throws Exception {
        mvc.perform(post("/api/session").contentType(MediaType.APPLICATION_JSON).content("{\"customer_id\":\"CUST-9999\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/session").contentType(MediaType.APPLICATION_JSON).content("{\"customer_id\":\"ACC-1001-SB\"}"))
                .andExpect(status().isBadRequest());
        String token = login(mvc, "CUST-1001");
        mvc.perform(chat(token, "   ")).andExpect(status().isBadRequest());
    }

    @Test
    void healthReportsModel() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.llm").value("scripted"));
    }
}
