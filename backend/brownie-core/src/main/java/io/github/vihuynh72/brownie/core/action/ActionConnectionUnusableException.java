package io.github.vihuynh72.brownie.core.action;

/**
 * The connection open now cannot be used to ask about this action: it is for
 * a different account than the one the action was carried out through.
 */
public class ActionConnectionUnusableException extends RuntimeException {

    public ActionConnectionUnusableException(long actionId) {
        super("No usable connection to the account action " + actionId + " used.");
    }
}
