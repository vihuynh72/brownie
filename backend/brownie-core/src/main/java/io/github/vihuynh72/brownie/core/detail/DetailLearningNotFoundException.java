package io.github.vihuynh72.brownie.core.detail;

/** The learning run asked to undo does not exist or is not the caller's. */
public class DetailLearningNotFoundException extends RuntimeException {

    public DetailLearningNotFoundException(long learningId) {
        super("No learning run " + learningId + " to undo.");
    }
}
