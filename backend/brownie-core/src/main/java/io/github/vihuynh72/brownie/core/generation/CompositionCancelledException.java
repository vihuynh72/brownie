package io.github.vihuynh72.brownie.core.generation;

/** Cancellation was requested and observed before a paid model call this run had not yet made. No call was placed for this attempt. */
public class CompositionCancelledException extends Exception {

    public CompositionCancelledException() {
        super("Composition was cancelled before its next model call.");
    }
}
