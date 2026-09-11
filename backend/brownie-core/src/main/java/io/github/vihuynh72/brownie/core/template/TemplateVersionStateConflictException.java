package io.github.vihuynh72.brownie.core.template;

/**
 * The template exists but has no open draft to act on, or the caller's
 * {@code expectedVersionNumber} no longer matches the current draft's own
 * number -- someone else's edit (or this same caller's earlier one) already
 * moved it forward. The message always names the actual current number so
 * a caller can refetch and retry rather than guess.
 */
public class TemplateVersionStateConflictException extends RuntimeException {

    public TemplateVersionStateConflictException(String message) {
        super(message);
    }
}
