package io.github.vihuynh72.brownie.core.artifact;

/** Wraps an unexpected failure talking to blob storage, so callers do not need to handle a checked {@code IOException}. */
public class ArtifactStorageException extends RuntimeException {

    public ArtifactStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
