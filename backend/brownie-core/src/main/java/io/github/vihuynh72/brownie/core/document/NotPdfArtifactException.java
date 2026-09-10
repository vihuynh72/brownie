package io.github.vihuynh72.brownie.core.document;

/** The artifact exists and is READY, but its own detected media type is not PDF. */
public class NotPdfArtifactException extends RuntimeException {

    public NotPdfArtifactException(long artifactId, Object actualMediaType) {
        super("Artifact " + artifactId + " is " + actualMediaType + ", not PDF; this extractor only reads PDF artifacts.");
    }
}
