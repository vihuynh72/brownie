package io.github.vihuynh72.brownie.core.artifact;

/** The uploaded content's own bytes do not match any allowed media type or package signature. */
public class UnsupportedArtifactTypeException extends RuntimeException {

    public UnsupportedArtifactTypeException(String message) {
        super(message);
    }
}
