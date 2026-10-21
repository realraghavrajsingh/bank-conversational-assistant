package io.lakeside.assistant.session;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** The customer's own accounts, captured at sign-in and used to scope every tool call. */
public record AccountRef(String accountId, String type, String product, String maskedNumber) {

    public static AccountRef from(JsonNode n) {
        return new AccountRef(n.path("account_id").asText(), n.path("type").asText(), n.path("product").asText(),
                n.path("masked_number").asText());
    }

    public static List<AccountRef> listFrom(JsonNode accounts) {
        List<AccountRef> out = new ArrayList<>();
        accounts.forEach(a -> out.add(from(a)));
        return List.copyOf(out);
    }
}
