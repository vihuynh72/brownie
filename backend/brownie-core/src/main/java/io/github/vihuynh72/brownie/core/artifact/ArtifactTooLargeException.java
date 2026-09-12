package io.github.vihuynh72.brownie.core.artifact;

/** The uploaded content exceeded the server's configured size limit. */
public class ArtifactTooLargeException extends RuntimeException {

    public ArtifactTooLargeException(String message) {
        super(message);
    }
}
