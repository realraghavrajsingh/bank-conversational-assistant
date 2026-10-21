package io.lakeside.assistant.web;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import io.lakeside.assistant.web.Dtos.ErrorBody;

/** Uniform {@code {error, message}} JSON errors for the UI. */
@RestControllerAdvice
class ApiErrorHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorBody> status(ResponseStatusException e) {
        int code = e.getStatusCode().value();
        String error = switch (code) {
            case 400 -> "bad_request";
            case 401 -> "unauthorized";
            case 404 -> "not_found";
            case 413 -> "too_large";
            case 429 -> "rate_limited";
            case 503 -> "unavailable";
            default -> "error";
        };
        return ResponseEntity.status(e.getStatusCode()).body(new ErrorBody(error, e.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorBody("bad_request", msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorBody("bad_request", "malformed JSON body"));
    }
}
