package io.github.vihuynh72.brownie.api.detail;

/** The value in a manual save is blank or past the length the key allows. */
public class SavedDetailValueException extends RuntimeException {

    public SavedDetailValueException(String message) {
        super(message);
    }
}
