package io.github.vihuynh72.brownie.core.support;

/** Support already has this access; a second grant of the same scope would only blur which one counts and when it ends. */
public class SupportGrantConflictException extends RuntimeException {

    public SupportGrantConflictException(String message) {
        super(message);
    }
}
