package io.github.vihuynh72.brownie.core.artifact;

/** No artifact by that ID exists in the caller's workspace -- whether it never existed or belongs to someone else. */
public class ArtifactNotFoundException extends RuntimeException {

    public ArtifactNotFoundException(long artifactId) {
        super("No artifact " + artifactId + " in this workspace.");
    }
}
