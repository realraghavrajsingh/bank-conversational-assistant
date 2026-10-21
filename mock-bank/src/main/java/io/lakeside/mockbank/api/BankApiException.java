package io.lakeside.mockbank.api;

import org.springframework.http.HttpStatus;

public class BankApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Object detail;

    public BankApiException(HttpStatus status, String code, String message, Object detail) {
        super(message);
        this.status = status;
        this.code = code;
        this.detail = detail;
    }

    public static BankApiException notFound(String what) {
        return new BankApiException(HttpStatus.NOT_FOUND, "not_found", what + " not found", null);
    }

    public static BankApiException badRequest(String message) {
        return new BankApiException(HttpStatus.BAD_REQUEST, "bad_request", message, null);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Object detail() {
        return detail;
    }
}
