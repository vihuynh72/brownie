package io.github.vihuynh72.brownie.core.job;

/** Raised when an existing key is reused with a different canonical request. */
public final class IdempotencyConflictException extends IllegalStateException {

    public IdempotencyConflictException(IdempotencyKey key, JobCommandType commandType) {
        super("Idempotency key '" + key.value() + "' was already used for " + commandType.operation() + " with different input.");
    }
}
