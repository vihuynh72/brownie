package io.github.vihuynh72.brownie.api.export;

/** An export command does not match the bounded typed request contract. */
public final class ExportRequestValidationException extends IllegalArgumentException {

    public ExportRequestValidationException(String message) {
        super(message);
    }
}
