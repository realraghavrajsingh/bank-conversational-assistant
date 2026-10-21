package io.lakeside.assistant.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import io.lakeside.assistant.support.Fixtures;
import io.lakeside.assistant.support.MockBankServer;
import io.lakeside.assistant.support.ScriptedLlmClient;

final class ApiTestSupport {

    private ApiTestSupport() {
    }

    @TestConfiguration
    static class ScriptedModel {
        @Bean
        @Primary
        ScriptedLlmClient scriptedLlm() {
            return new ScriptedLlmClient();
        }
    }

    static void bankProperties(DynamicPropertyRegistry registry) {
        registry.add("assistant.bank.base-url", MockBankServer::baseUrl);
        registry.add("assistant.bank.service-key", () -> MockBankServer.SERVICE_KEY);
    }

    static String login(MockMvc mvc, String customerId) throws Exception {
        String body = mvc.perform(post("/api/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customer_id\":\"" + customerId + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Fixtures.MAPPER.readTree(body).path("token").asText();
    }

    static MockHttpServletRequestBuilder chat(String token, String message) {
        return post("/api/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(Fixtures.MAPPER.createObjectNode().put("message", message).toString());
    }
}
