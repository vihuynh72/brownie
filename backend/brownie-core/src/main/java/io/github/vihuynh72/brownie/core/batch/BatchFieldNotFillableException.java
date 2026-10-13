package io.github.vihuynh72.brownie.core.batch;

/** A mapping names a field a spreadsheet column cannot fill -- one cell is one value, not a list. */
public class BatchFieldNotFillableException extends RuntimeException {

    private final String fieldId;

    public BatchFieldNotFillableException(String fieldId) {
        super("Field " + fieldId + " is not a scalar field a spreadsheet column can fill.");
        this.fieldId = fieldId;
    }

    public String fieldId() {
        return fieldId;
    }
}
