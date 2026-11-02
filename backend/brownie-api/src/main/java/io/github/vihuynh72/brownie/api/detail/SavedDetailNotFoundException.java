package io.github.vihuynh72.brownie.api.detail;

/** The key in the path is not one of the canonical {@code DetailKey}s. */
public class SavedDetailNotFoundException extends RuntimeException {

    public SavedDetailNotFoundException(String key) {
        super("No saved-detail key \"" + key + "\" exists.");
    }
}
