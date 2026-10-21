package io.lakeside.assistant.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import io.lakeside.assistant.session.ConversationSession;
import io.lakeside.assistant.session.SessionStore;

final class BearerAuth {

    private static final String PREFIX = "Bearer ";

    private BearerAuth() {
    }

    static String token(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)) {
            return null;
        }
        return authorizationHeader.substring(PREFIX.length()).strip();
    }

    static ConversationSession require(SessionStore sessions, String authorizationHeader) {
        String token = token(authorizationHeader);
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing bearer token");
        }
        return sessions.get(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session expired or invalid"));
    }
}
