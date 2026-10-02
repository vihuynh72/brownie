package io.github.vihuynh72.brownie.core.template;

/**
 * A request to change fill spots that cannot be made as asked: a label that
 * is not one, a spot the form does not have, too many changes, or a form
 * that would be left with none. The message says what to do, in words a
 * person can act on.
 */
public class FillSpotChangeInvalidException extends RuntimeException {

    public FillSpotChangeInvalidException(String message) {
        super(message);
    }
}
