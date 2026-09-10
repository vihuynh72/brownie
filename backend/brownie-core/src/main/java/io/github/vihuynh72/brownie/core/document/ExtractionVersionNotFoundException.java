package io.github.vihuynh72.brownie.core.document;

/** The artifact exists, but no extraction has run for it yet under the extractor's current parser version. */
public class ExtractionVersionNotFoundException extends RuntimeException {

    public ExtractionVersionNotFoundException(long artifactId) {
        super("No extraction has been run yet for artifact " + artifactId + ".");
    }
}
