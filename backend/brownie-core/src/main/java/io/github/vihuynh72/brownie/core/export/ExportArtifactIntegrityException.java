package io.github.vihuynh72.brownie.core.export;

/**
 * An artifact this export was about to ship no longer matches the hash
 * its own validation manifest recorded -- storage corruption, an
 * unexpected mutation, or a bug elsewhere, but never something this
 * export silently ships anyway.
 */
public class ExportArtifactIntegrityException extends RuntimeException {

    public ExportArtifactIntegrityException(long artifactId, String expectedSha256, String actualSha256) {
        super("Artifact " + artifactId + " no longer matches its own validation manifest's recorded hash "
                + "(expected " + expectedSha256 + ", found " + actualSha256 + ").");
    }
}
