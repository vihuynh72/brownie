package io.github.vihuynh72.brownie.core.batch;

/** A field is mapped twice -- one column per field, so the request cannot name two sources for one value. */
public class BatchDuplicateMappingException extends RuntimeException {

    private final String fieldId;

    public BatchDuplicateMappingException(String fieldId) {
        super("Field " + fieldId + " is mapped more than once.");
        this.fieldId = fieldId;
    }

    public String fieldId() {
        return fieldId;
    }
}
