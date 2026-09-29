package io.github.vihuynh72.brownie.core.action;

/** No such action belongs to this person in this workspace. */
public class ActionNotFoundException extends RuntimeException {

    public ActionNotFoundException(long actionId) {
        super("Action " + actionId + " was not found.");
    }
}
