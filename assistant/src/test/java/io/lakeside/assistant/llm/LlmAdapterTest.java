package io.lakeside.assistant.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.lakeside.assistant.support.Fixtures;

/** Wire-format translation and response parsing for each provider, without network access. */
class LlmAdapterTest {

    private static final List<ToolSpec> TOOLS = List.of(new ToolSpec("get_balance", "balance",
            (ObjectNode) Fixtures.MAPPER.createObjectNode().put("type", "object")));

    private static final List<ChatMessage> HISTORY = List.of(
            ChatMessage.user("balance?"),
            ChatMessage.assistant(null, List.of(new ToolCall("call_1", "get_balance",
                    Fixtures.MAPPER.createObjectNode().put("account_id", "A"))), null, "other"),
            ChatMessage.tool("call_1", "get_balance", "{\"available_balance\":\"10.00\"}"));

    @Test
    void geminiTranslatesHistoryAndKeepsThoughtSignatures() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://llm.test/v1beta");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiClient gemini = new GeminiClient(builder.build(), Fixtures.MAPPER, "m", "test-key");

        server.expect(requestTo(endsWith("/v1beta/models/m:generateContent")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(jsonPath("$.contents[*].role").value(contains("user", "model", "user")))
                .andExpect(jsonPath("$.contents[1].parts[0].functionCall.name").value("get_balance"))
                .andExpect(jsonPath("$.contents[2].parts[0].functionResponse.response.result.available_balance").value("10.00"))
                .andExpect(jsonPath("$.tools[0].functionDeclarations[0].name").value("get_balance"))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[
                          {"text":"thinking...","thought":true},
                          {"functionCall":{"name":"get_balance","args":{"account_id":"A"}},"thoughtSignature":"sig-1"}]}}],
                         "usageMetadata":{"promptTokenCount":11,"candidatesTokenCount":3}}""", MediaType.APPLICATION_JSON));

        LlmResponse resp = gemini.complete("sys", HISTORY, TOOLS);

        assertThat(resp.text()).isNull(); // thought parts are never surfaced
        assertThat(resp.toolCalls()).singleElement().satisfies(tc -> {
            assertThat(tc.name()).isEqualTo("get_balance");
            assertThat(tc.id()).startsWith(ToolCall.SYNTHETIC_ID_PREFIX);
            assertThat(tc.arguments().path("account_id").asText()).isEqualTo("A");
        });
        assertThat(resp.usage()).containsEntry("input_tokens", 11);
        server.verify();

        // Replaying that turn sends the provider-native content back verbatim, signature included.
        server.reset();
        server.expect(requestTo(endsWith(":generateContent")))
                .andExpect(jsonPath("$.contents[1].parts[1].thoughtSignature").value("sig-1"))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[{"text":"₹10.00"}]}}]}""", MediaType.APPLICATION_JSON));
        LlmResponse second = gemini.complete("sys", List.of(
                ChatMessage.user("balance?"),
                ChatMessage.assistant(null, resp.toolCalls(), resp.providerRaw(), GeminiClient.NAME),
                ChatMessage.tool(resp.toolCalls().get(0).id(), "get_balance", "{}")), TOOLS);
        assertThat(second.text()).isEqualTo("₹10.00");
        server.verify();
    }

    @Test
    void geminiWithoutKeyOrOnServerErrorRaisesLlmException() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://llm.test/v1beta");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient http = builder.build();

        assertThat(new GeminiClient(http, Fixtures.MAPPER, "m", "").configurationProblem()).contains("GEMINI_API_KEY");
        assertThatThrownBy(() -> new GeminiClient(http, Fixtures.MAPPER, "m", "").complete("s", HISTORY, TOOLS))
                .isInstanceOf(LlmException.class);

        server.expect(requestTo(endsWith(":generateContent"))).andRespond(withServerError());
        assertThatThrownBy(() -> new GeminiClient(http, Fixtures.MAPPER, "m", "k").complete("s", HISTORY, TOOLS))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("500");
    }

    @Test
    void openAiTranslatesHistoryAndParsesToolCalls() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://llm.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatClient openai = new OpenAiCompatClient(builder.build(), Fixtures.MAPPER, "m", "k");

        server.expect(requestTo("https://llm.test/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer k"))
                .andExpect(jsonPath("$.messages[*].role").value(contains("system", "user", "assistant", "tool")))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].function.name").value("get_balance"))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].function.arguments").value("{\"account_id\":\"A\"}"))
                .andExpect(jsonPath("$.messages[3].tool_call_id").value("call_1"))
                .andExpect(jsonPath("$.tools[0].function.name").value("get_balance"))
                .andRespond(withSuccess("""
                        {"choices":[{"index":0,"message":{"role":"assistant","content":null,
                          "tool_calls":[{"id":"call_2","type":"function",
                            "function":{"name":"search_transactions","arguments":"{\\"query\\":\\"uber\\"}"}}]}}],
                         "usage":{"prompt_tokens":5,"completion_tokens":7}}""", MediaType.APPLICATION_JSON));

        LlmResponse resp = openai.complete("sys", HISTORY, TOOLS);

        assertThat(resp.text()).isNull();
        assertThat(resp.toolCalls()).singleElement().satisfies(tc -> {
            assertThat(tc.id()).isEqualTo("call_2");
            assertThat(tc.arguments().path("query").asText()).isEqualTo("uber");
        });
        assertThat(resp.usage()).containsEntry("output_tokens", 7);
        server.verify();
    }
}
