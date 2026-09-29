package io.github.vihuynh72.brownie.core.action;

/** This kind of action is not offered by this deployment. */
public class ActionNotOfferedException extends RuntimeException {

    private final ActionType type;

    public ActionNotOfferedException(ActionType type) {
        super(type + " is not offered here.");
        this.type = type;
    }

    public ActionType type() {
        return type;
    }
}
