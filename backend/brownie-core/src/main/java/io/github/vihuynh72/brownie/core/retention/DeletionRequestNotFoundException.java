package io.github.vihuynh72.brownie.core.retention;

/** No deletion request with this id is visible to the caller in this workspace. */
public class DeletionRequestNotFoundException extends RuntimeException {

    public DeletionRequestNotFoundException(long requestId) {
        super("No deletion request " + requestId + " exists in this workspace.");
    }
}
