package io.github.vihuynh72.brownie.core.action;

/** The hash presented with an approval is not the hash of what this action would do: what was approved is not this. */
public class ActionPayloadMismatchException extends RuntimeException {

    public ActionPayloadMismatchException(long actionId) {
        super("The approval presented for action " + actionId + " does not match what it would do.");
    }
}
