package io.github.vihuynh72.brownie.core.template;

/**
 * A new spot was made in the file but lands where the filler would never
 * write a value, so the form is left as it was rather than given a spot that
 * can never be filled.
 */
public class FillSpotNotPlacedException extends RuntimeException {

    private final String fieldId;

    public FillSpotNotPlacedException(String fieldId) {
        super("The new fill spot '" + fieldId + "' is not somewhere a value can be written.");
        this.fieldId = fieldId;
    }

    public String fieldId() {
        return fieldId;
    }
}
