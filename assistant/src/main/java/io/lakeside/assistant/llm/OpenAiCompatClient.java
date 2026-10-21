package io.lakeside.assistant.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * OpenAI Chat Completions adapter. Works with OpenAI and any compatible endpoint
 * (Ollama, vLLM, Groq, LiteLLM / Azure gateways) via {@code OPENAI_BASE_URL}.
 */
public class OpenAiCompatClient implements LlmClient {

    public static final String NAME = "openai";

    private final RestClient http;
    private final ObjectMapper mapper;
    private final String model;
    private final String apiKey;

    public OpenAiCompatClient(RestClient http, ObjectMapper mapper, String model, String apiKey) {
        this.http = http;
        this.mapper = mapper;
        this.model = model;
        this.apiKey = apiKey;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public LlmResponse complete(String system, List<ChatMessage> messages, List<ToolSpec> tools) {
        ObjectNode body = mapper.createObjectNode().put("model", model);
        body.set("messages", toWire(system, messages));
        ArrayNode toolArray = body.putArray("tools");
        for (ToolSpec t : tools) {
            ObjectNode fn = toolArray.addObject().put("type", "function").putObject("function");
            fn.put("name", t.name()).put("description", t.description()).set("parameters", t.parameters());
        }

        JsonNode response;
        try {
            RestClient.RequestBodySpec req = http.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.isBlank()) {
                req = req.header("Authorization", "Bearer " + apiKey); // local endpoints (Ollama) need none
            }
            response = req.body(body).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            String b = e.getResponseBodyAsString();
            throw new LlmException("openai-compatible error " + e.getStatusCode().value() + ": "
                    + (b.length() > 300 ? b.substring(0, 300) + "..." : b), e);
        } catch (RestClientException e) {
            throw new LlmException("openai-compatible provider unavailable: " + e.getMessage(), e);
        }
        return parse(response);
    }

    ArrayNode toWire(String system, List<ChatMessage> messages) {
        ArrayNode out = mapper.createArrayNode();
        out.addObject().put("role", "system").put("content", system);
        for (ChatMessage m : messages) {
            switch (m.role()) {
                case USER -> out.addObject().put("role", "user").put("content", m.content() == null ? "" : m.content());
                case ASSISTANT -> {
                    ObjectNode msg = out.addObject().put("role", "assistant");
                    if (m.content() == null) {
                        msg.putNull("content");
                    } else {
                        msg.put("content", m.content());
                    }
                    if (m.hasToolCalls()) {
                        ArrayNode calls = msg.putArray("tool_calls");
                        for (ToolCall tc : m.toolCalls()) {
                            calls.addObject().put("id", tc.id()).put("type", "function")
                                    .putObject("function").put("name", tc.name()).put("arguments", tc.arguments().toString());
                        }
                    }
                }
                case TOOL -> out.addObject().put("role", "tool").put("tool_call_id", m.toolCallId()).put("content", m.content());
            }
        }
        return out;
    }

    LlmResponse parse(JsonNode response) {
        JsonNode message = response == null ? null : response.path("choices").path(0).get("message");
        if (message == null) {
            throw new LlmException("openai-compatible provider returned no message");
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode tc : message.path("tool_calls")) {
            String rawArgs = tc.path("function").path("arguments").asText("{}");
            ObjectNode args;
            try {
                JsonNode parsed = mapper.readTree(rawArgs.isBlank() ? "{}" : rawArgs);
                args = parsed instanceof ObjectNode o ? o : mapper.createObjectNode().put("_malformed_arguments", rawArgs);
            } catch (JsonProcessingException e) {
                args = mapper.createObjectNode().put("_malformed_arguments", rawArgs);
            }
            calls.add(new ToolCall(tc.path("id").asText(), tc.path("function").path("name").asText(), args));
        }
        JsonNode content = message.get("content");
        String text = content == null || content.isNull() ? null : content.asText().strip();
        Map<String, Integer> usage = new HashMap<>();
        JsonNode u = response.path("usage");
        if (u.isObject()) {
            usage.put("input_tokens", u.path("prompt_tokens").asInt());
            usage.put("output_tokens", u.path("completion_tokens").asInt());
        }
        return new LlmResponse(text == null || text.isEmpty() ? null : text, calls, null, usage);
    }
}
