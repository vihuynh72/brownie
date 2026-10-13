package io.github.vihuynh72.brownie.core.batch;

/** A mapping names a field the pinned template version does not define. */
public class BatchUnknownFieldException extends RuntimeException {

    private final String fieldId;

    public BatchUnknownFieldException(String fieldId) {
        super("Field " + fieldId + " is not a field of this template version.");
        this.fieldId = fieldId;
    }

    public String fieldId() {
        return fieldId;
    }
}
