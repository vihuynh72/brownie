package io.github.vihuynh72.brownie.core.generation;

/** Cancellation was requested before a model request (a first try or a retry) was sent; nothing was spent on it. */
public class ModelCallCancelledException extends Exception {

    public ModelCallCancelledException() {
        super("Cancellation was requested before the model request was sent.");
    }
}
