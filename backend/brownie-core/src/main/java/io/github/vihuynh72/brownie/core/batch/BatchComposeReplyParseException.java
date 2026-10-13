package io.github.vihuynh72.brownie.core.batch;

/**
 * The compose reply was not the strict object the request asked for:
 * wrong JSON, a missing or unknown field id, or a place entry that is
 * not {@code {"text": ...}}. A reply that fails here is rejected whole --
 * picking out the parts that did parse would mean trusting a reply that
 * already showed it cannot follow the shape.
 */
public final class BatchComposeReplyParseException extends RuntimeException {

    public BatchComposeReplyParseException(String message) {
        super(message);
    }

    public BatchComposeReplyParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
