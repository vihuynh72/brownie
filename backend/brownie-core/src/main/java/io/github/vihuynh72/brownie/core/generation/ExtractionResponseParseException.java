package io.github.vihuynh72.brownie.core.generation;

/**
 * A model reply that was syntactically valid JSON but did not match the
 * specific extraction schema it was asked to follow (a missing field, a
 * date that does not parse, a wrong type) -- schema conformance
 * constrains structure, not truth, and this is what a structurally
 * invalid result looks like once someone actually tries to use it. This
 * task deliberately does not retry on this exception; the single
 * permitted structured-output repair attempt is later work.
 */
public class ExtractionResponseParseException extends Exception {

    public ExtractionResponseParseException(String message) {
        super(message);
    }

    public ExtractionResponseParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
