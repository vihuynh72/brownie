package io.github.vihuynh72.brownie.core.retention;

/** Only the owner of a workspace may delete it. A member who is not the owner is refused, not told it is missing. */
public class WorkspaceDeletionNotPermittedException extends RuntimeException {

    public WorkspaceDeletionNotPermittedException() {
        super("Only the owner of a workspace can delete it.");
    }
}
