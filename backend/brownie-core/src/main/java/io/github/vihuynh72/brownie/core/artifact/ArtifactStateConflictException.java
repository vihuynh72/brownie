package io.github.vihuynh72.brownie.core.artifact;

/** The artifact exists but is not in a state that allows the requested operation right now. */
public class ArtifactStateConflictException extends RuntimeException {

    public ArtifactStateConflictException(String message) {
        super(message);
    }
}
