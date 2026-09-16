package io.github.vihuynh72.brownie.core.export;

/**
 * An artifact this export was about to ship no longer matches the hash
 * its own validation manifest recorded -- storage corruption, an
 * unexpected mutation, or a bug elsewhere, but never something this
 * export silently ships anyway.
 */
public class ExportArtifactIntegrityException extends RuntimeException {

    private final long artifactId;
    private final String expectedSha256;
    private final String actualSha256;

    public ExportArtifactIntegrityException(long artifactId, String expectedSha256, String actualSha256) {
        super("Artifact " + artifactId + " no longer matches its own validation manifest's recorded hash "
                + "(expected " + expectedSha256 + ", found " + actualSha256 + ").");
        this.artifactId = artifactId;
        this.expectedSha256 = expectedSha256;
        this.actualSha256 = actualSha256;
    }

    public long artifactId() {
        return artifactId;
    }

    public String expectedSha256() {
        return expectedSha256;
    }

    public String actualSha256() {
        return actualSha256;
    }
}
