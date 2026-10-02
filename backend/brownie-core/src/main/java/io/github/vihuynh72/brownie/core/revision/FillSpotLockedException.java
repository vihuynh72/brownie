package io.github.vihuynh72.brownie.core.revision;

/**
 * A change to a document's fill spots would drop a value a person locked:
 * its spot is being taken away, or the version the document is moving to
 * does not have it. A locked value is never dropped quietly, so nothing is
 * changed; the person unlocks it first if they mean it.
 */
public class FillSpotLockedException extends RuntimeException {

    private final String fieldId;

    public FillSpotLockedException(String fieldId) {
        super("The value of '" + fieldId + "' is locked, and this change would remove it.");
        this.fieldId = fieldId;
    }

    public String fieldId() {
        return fieldId;
    }
}
