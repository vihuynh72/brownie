package io.github.vihuynh72.brownie.core.document;

/**
 * The artifact exists and is READY, but its own detected media type is not
 * DOCX -- distinct from {@code UnsupportedArtifactTypeException}, which
 * means the bytes did not match any allowed type at all. This one means
 * they matched a real, allowed type, just not the one this extractor reads.
 */
public class NotDocxArtifactException extends RuntimeException {

    public NotDocxArtifactException(long artifactId, Object actualMediaType) {
        super("Artifact " + artifactId + " is " + actualMediaType + ", not DOCX; this extractor only reads DOCX artifacts.");
    }
}
