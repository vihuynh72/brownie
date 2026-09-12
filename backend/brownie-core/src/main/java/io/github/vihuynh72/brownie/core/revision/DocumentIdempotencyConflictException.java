package io.github.vihuynh72.brownie.core.revision;

import io.github.vihuynh72.brownie.core.job.IdempotencyKey;

import java.util.Objects;

/** Raised when a document mutation key is reused with different input. */
public final class DocumentIdempotencyConflictException extends IllegalStateException {

    private final IdempotencyKey idempotencyKey;
    private final DocumentCommandType commandType;

    public DocumentIdempotencyConflictException(IdempotencyKey idempotencyKey, DocumentCommandType commandType) {
        super("Idempotency key '" + Objects.requireNonNull(idempotencyKey, "idempotencyKey").value()
                + "' was already used for " + Objects.requireNonNull(commandType, "commandType").operation()
                + " with different input.");
        this.idempotencyKey = idempotencyKey;
        this.commandType = commandType;
    }

    public IdempotencyKey idempotencyKey() {
        return idempotencyKey;
    }

    public DocumentCommandType commandType() {
        return commandType;
    }
}
