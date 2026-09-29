package io.github.vihuynh72.brownie.core.action;

import java.util.Objects;

/** Something an approved action depends on changed before it could be sent; the action ends and nothing is sent. */
public class ActionChangedException extends RuntimeException {

    private final ActionFailure failure;

    public ActionChangedException(ActionFailure failure) {
        super("What this action depends on changed before it was sent (" + failure + ").");
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public ActionFailure failure() {
        return failure;
    }
}
