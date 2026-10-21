package io.lakeside.assistant.bank;

import com.fasterxml.jackson.databind.JsonNode;

/** Core banking rejected the request (4xx): not found, conflict, validation. */
public class BankApiError extends RuntimeException {

    private final int status;
    private final String code;
    private final transient JsonNode detail;

    public BankApiError(int status, String code, String message, JsonNode detail) {
        super("core banking " + status + " " + code + ": " + message);
        this.status = status;
        this.code = code;
        this.detail = detail;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String bankMessage() {
        String m = getMessage();
        int i = m.indexOf(": ");
        return i < 0 ? m : m.substring(i + 2);
    }

    public JsonNode detail() {
        return detail;
    }
}
