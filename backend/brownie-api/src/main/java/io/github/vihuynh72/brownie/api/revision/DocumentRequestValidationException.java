package io.github.vihuynh72.brownie.api.revision;

/** A document command does not match the bounded typed request contract. */
public final class DocumentRequestValidationException extends IllegalArgumentException {

    public DocumentRequestValidationException(String message) {
        super(message);
    }
}
