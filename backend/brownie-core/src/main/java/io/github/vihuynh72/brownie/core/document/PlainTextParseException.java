package io.github.vihuynh72.brownie.core.document;

/** The artifact's bytes are not well-formed UTF-8 -- the one encoding this pilot supports (see the plan's own "additional encodings after testing" deferral). */
public class PlainTextParseException extends RuntimeException {

    public PlainTextParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
