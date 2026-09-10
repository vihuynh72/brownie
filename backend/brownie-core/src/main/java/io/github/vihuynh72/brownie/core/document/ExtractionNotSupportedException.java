package io.github.vihuynh72.brownie.core.document;

/** The artifact is READY, but its detected media type has no extractor at all yet (for example plain text). */
public class ExtractionNotSupportedException extends RuntimeException {

    public ExtractionNotSupportedException(long artifactId, Object actualMediaType) {
        super("Artifact " + artifactId + " is " + actualMediaType + "; no structural extractor exists for that type yet.");
    }
}
