package io.lakeside.assistant.tools;

import io.lakeside.assistant.session.ConversationSession;

/** What a tool may know about the caller: only the authenticated session. */
public record ToolContext(ConversationSession session) {
}
