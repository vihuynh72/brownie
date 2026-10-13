package io.github.vihuynh72.brownie.core.batch;

/** The idempotency key already created a batch from a different request -- a key is never two batches. */
public class BatchIdempotencyConflictException extends RuntimeException {

    public BatchIdempotencyConflictException() {
        super("This idempotency key was already used for a different batch request.");
    }
}
