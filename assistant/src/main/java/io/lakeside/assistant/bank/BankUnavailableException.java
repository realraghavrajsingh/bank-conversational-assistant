package io.lakeside.assistant.bank;

/** Core banking is down, timing out or returned a 5xx. */
public class BankUnavailableException extends RuntimeException {

    public BankUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
