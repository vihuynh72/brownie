package io.github.vihuynh72.brownie.core.prepare;

/** A naming reply that was JSON but not the shape it was asked for; the part of the document it covers is named by the rules instead. */
public class FillSpotResponseParseException extends Exception {

    public FillSpotResponseParseException(String message) {
        super(message);
    }

    public FillSpotResponseParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
