package io.lakeside.assistant.tools;

/** A tool refused to run for a reason the model should see and can act on. */
class ToolFailure extends RuntimeException {

    private final String code;

    ToolFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
