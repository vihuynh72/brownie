package io.github.vihuynh72.brownie.core.mcp;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * One line of the durable record of what a connected app did. The code is
 * a short machine token explaining an outcome; arguments a caller typed
 * are deliberately not part of the record, so reading it never replays
 * user-provided text.
 */
public record McpToolCall(
        long id,
        long grantId,
        Long documentId,
        String tool,
        McpToolCallOutcome outcome,
        String code,
        OffsetDateTime at) {

    public McpToolCall {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(at, "at");
    }
}
