package io.github.vihuynh72.brownie.core.action;

/** This person's role in the workspace does not let them act in connected accounts. */
public class ActionNotPermittedException extends RuntimeException {

    public ActionNotPermittedException() {
        super("This role does not allow changes in connected accounts.");
    }
}
