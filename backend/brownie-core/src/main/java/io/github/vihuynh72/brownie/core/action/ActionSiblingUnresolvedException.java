package io.github.vihuynh72.brownie.core.action;

/**
 * The same change is already under way through another action, or an
 * earlier one may have happened and nobody knows yet: sending it again could
 * make it twice. The earlier one has to be checked, or its person has to say
 * they checked, first.
 */
public class ActionSiblingUnresolvedException extends RuntimeException {

    public ActionSiblingUnresolvedException(long actionId) {
        super("The change action " + actionId + " makes is already under way or unresolved through another action.");
    }
}
