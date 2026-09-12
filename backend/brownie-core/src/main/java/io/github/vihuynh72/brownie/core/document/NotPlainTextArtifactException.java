package io.github.vihuynh72.brownie.core.document;

/** The artifact exists and is READY, but its own detected media type is not plain text. */
public class NotPlainTextArtifactException extends RuntimeException {

    public NotPlainTextArtifactException(long artifactId, Object actualMediaType) {
        super("Artifact " + artifactId + " is " + actualMediaType + ", not plain text; this extractor only reads plain-text artifacts.");
    }
}
