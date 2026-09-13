package io.github.vihuynh72.brownie.core.model;

/**
 * The model call could not be completed at all -- a network failure, a
 * timeout, or a provider error that has nothing to do with what this
 * request asked for. Distinct from every {@link ModelCompletion} variant
 * the same way {@code MalwareScanner} distinguishes "the scanner was
 * unavailable" from "the scanner said this is clean": a caller must never
 * mistake a transport failure for a content outcome it can make a
 * decision from. {@code retryable} tells a caller's own retry policy
 * whether trying again later has any chance of succeeding -- an expired
 * or rejected credential never will, a rate limit or a provider outage
 * might.
 */
public class ModelTransportException extends Exception {

    private final boolean retryable;

    public ModelTransportException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
