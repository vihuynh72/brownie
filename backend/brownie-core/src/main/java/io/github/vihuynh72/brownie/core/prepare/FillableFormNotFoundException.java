package io.github.vihuynh72.brownie.core.prepare;

/** No fillable copy has been made of the artifact, or the artifact is not one this person can see. */
public class FillableFormNotFoundException extends RuntimeException {

    public FillableFormNotFoundException(long artifactId) {
        super("No fillable copy has been made of artifact " + artifactId + ".");
    }
}
