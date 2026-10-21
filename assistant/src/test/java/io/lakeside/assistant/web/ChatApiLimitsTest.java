package io.lakeside.assistant.web;

import static io.lakeside.assistant.support.Steps.say;
import static io.lakeside.assistant.web.ApiTestSupport.chat;
import static io.lakeside.assistant.web.ApiTestSupport.login;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import io.lakeside.assistant.support.ScriptedLlmClient;

@SpringBootTest(properties = {"assistant.max-message-chars=20", "assistant.max-turns-per-minute=2"})
@AutoConfigureMockMvc
@Import(ApiTestSupport.ScriptedModel.class)
class ChatApiLimitsTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        ApiTestSupport.bankProperties(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ScriptedLlmClient llm;

    @Test
    void enforcesMessageLengthLimit() throws Exception {
        String token = login(mvc, "CUST-1001");
        mvc.perform(chat(token, "x".repeat(50)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error").value("too_large"));
    }

    @Test
    void enforcesPerSessionRateLimit() throws Exception {
        llm.script(say("Hello!"), say("Hello!"));
        String token = login(mvc, "CUST-1001");
        mvc.perform(chat(token, "hi")).andExpect(status().isOk());
        mvc.perform(chat(token, "hi")).andExpect(status().isOk());
        mvc.perform(chat(token, "hi"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("rate_limited"));
    }
}
