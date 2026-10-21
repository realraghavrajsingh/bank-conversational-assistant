package io.lakeside.assistant.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * Google Gemini adapter over the {@code generateContent} REST API.
 * Automatic function calling is not used: the orchestrator executes every tool itself so it
 * can enforce scoping, confirmation and grounding.
 */
public class GeminiClient implements LlmClient {

    public static final String NAME = "gemini";

    private final RestClient http;
    private final ObjectMapper mapper;
    private final String model;
    private final String apiKey;

    public GeminiClient(RestClient http, ObjectMapper mapper, String model, String apiKey) {
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
    public String configurationProblem() {
        return apiKey == null || apiKey.isBlank() ? "GEMINI_API_KEY is not set" : null;
    }

    @Override
    public LlmResponse complete(String system, List<ChatMessage> messages, List<ToolSpec> tools) {
        if (configurationProblem() != null) {
            throw new LlmException(configurationProblem());
        }
        ObjectNode body = mapper.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
        body.set("contents", toContents(messages));
        ArrayNode declarations = body.putArray("tools").addObject().putArray("functionDeclarations");
        for (ToolSpec t : tools) {
            declarations.addObject()
                    .put("name", t.name())
                    .put("description", t.description())
                    .set("parametersJsonSchema", t.parameters());
        }
        body.putObject("toolConfig").putObject("functionCallingConfig").put("mode", "AUTO");

        JsonNode response;
        try {
            response = http.post()
                    .uri("/models/{model}:generateContent", model)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new LlmException("gemini error " + e.getStatusCode().value() + ": " + abbreviate(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            throw new LlmException("gemini unavailable: " + e.getMessage(), e);
        }
        return parse(response);
    }

    ArrayNode toContents(List<ChatMessage> messages) {
        List<ObjectNode> contents = new ArrayList<>();
        List<ObjectNode> pendingResponses = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m.role() == ChatMessage.Role.TOOL) {
                ObjectNode fr = mapper.createObjectNode();
                ObjectNode inner = fr.putObject("functionResponse");
                if (m.toolCallId() != null && !m.toolCallId().startsWith(ToolCall.SYNTHETIC_ID_PREFIX)) {
                    inner.put("id", m.toolCallId());
                }
                inner.put("name", m.toolName());
                inner.putObject("response").set("result", readJson(m.content()));
                pendingResponses.add(fr);
                continue;
            }
            flush(contents, pendingResponses);
            if (m.role() == ChatMessage.Role.USER) {
                contents.add(content("user", List.of(textPart(m.content()))));
            } else if (NAME.equals(m.provider()) && m.providerRaw() != null) {
                contents.add((ObjectNode) m.providerRaw().deepCopy()); // keeps thought signatures intact
            } else {
                List<ObjectNode> parts = new ArrayList<>();
                if (m.content() != null && !m.content().isBlank()) {
                    parts.add(textPart(m.content()));
                }
                for (ToolCall tc : m.toolCalls()) {
                    ObjectNode part = mapper.createObjectNode();
                    ObjectNode fc = part.putObject("functionCall");
                    if (tc.hasProviderId()) {
                        fc.put("id", tc.id());
                    }
                    fc.put("name", tc.name());
                    fc.set("args", tc.arguments());
                    parts.add(part);
                }
                contents.add(content("model", parts));
            }
        }
        flush(contents, pendingResponses);

        // The API expects alternating roles; merge adjacent turns with the same role.
        ArrayNode merged = mapper.createArrayNode();
        ObjectNode previous = null;
        for (ObjectNode c : contents) {
            if (previous != null && previous.path("role").asText().equals(c.path("role").asText())) {
                ((ArrayNode) previous.get("parts")).addAll((ArrayNode) c.get("parts"));
            } else {
                previous = c.deepCopy();
                merged.add(previous);
            }
        }
        return merged;
    }

    private void flush(List<ObjectNode> contents, List<ObjectNode> pendingResponses) {
        if (!pendingResponses.isEmpty()) {
            contents.add(content("user", pendingResponses));
            pendingResponses.clear();
        }
    }

    private ObjectNode content(String role, List<ObjectNode> parts) {
        ObjectNode c = mapper.createObjectNode().put("role", role);
        ArrayNode arr = c.putArray("parts");
        parts.forEach(arr::add);
        return c;
    }

    private ObjectNode textPart(String text) {
        return mapper.createObjectNode().put("text", text == null ? "" : text);
    }

    private JsonNode readJson(String json) {
        try {
            return mapper.readTree(json == null ? "{}" : json);
        } catch (JsonProcessingException e) {
            return mapper.getNodeFactory().textNode(json);
        }
    }

    LlmResponse parse(JsonNode response) {
        JsonNode candidate = response == null ? null : response.path("candidates").path(0);
        JsonNode content = candidate == null ? null : candidate.get("content");
        if (content == null || !content.has("parts")) {
            String reason = candidate == null ? "n/a" : candidate.path("finishReason").asText("n/a");
            throw new LlmException("gemini returned no content (finishReason=" + reason + ")");
        }
        List<ToolCall> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content.get("parts")) {
            if (part.has("functionCall")) {
                JsonNode fc = part.get("functionCall");
                String id = fc.hasNonNull("id") ? fc.get("id").asText()
                        : ToolCall.SYNTHETIC_ID_PREFIX + UUID.randomUUID().toString().substring(0, 8);
                ObjectNode args = fc.get("args") instanceof ObjectNode o ? o.deepCopy() : mapper.createObjectNode();
                calls.add(new ToolCall(id, fc.path("name").asText(), args));
            } else if (part.has("text") && !part.path("thought").asBoolean(false)) {
                text.append(part.get("text").asText());
            }
        }
        Map<String, Integer> usage = new HashMap<>();
        JsonNode meta = response.path("usageMetadata");
        if (meta.isObject()) {
            usage.put("input_tokens", meta.path("promptTokenCount").asInt());
            usage.put("output_tokens", meta.path("candidatesTokenCount").asInt());
        }
        String t = text.toString().strip();
        return new LlmResponse(t.isEmpty() ? null : t, calls, content.deepCopy(), usage);
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
