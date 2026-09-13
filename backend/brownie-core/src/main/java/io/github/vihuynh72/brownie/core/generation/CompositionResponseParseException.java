package io.github.vihuynh72.brownie.core.generation;

/**
 * A composition reply that was syntactically valid JSON but did not match
 * the requested schema, or whose composed text violates a rule this class
 * checks after parsing (for example {@code MaxTextLength}) -- the same
 * "structure is not truth, and still needs checking" role {@link
 * ExtractionResponseParseException} plays for extraction.
 */
public class CompositionResponseParseException extends Exception {

    public CompositionResponseParseException(String message) {
        super(message);
    }

    public CompositionResponseParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
