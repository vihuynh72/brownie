package io.github.vihuynh72.brownie.core.batch;

import java.util.List;

/**
 * A required field has no column mapped to it -- the batch would make
 * documents missing something their template demands, so the batch is
 * refused whole rather than filled badly.
 */
public class BatchRequiredFieldsUnmappedException extends RuntimeException {

    private final List<String> fieldIds;

    public BatchRequiredFieldsUnmappedException(List<String> fieldIds) {
        super("Required fields have no column mapped: " + String.join(", ", fieldIds));
        this.fieldIds = List.copyOf(fieldIds);
    }

    public List<String> fieldIds() {
        return fieldIds;
    }
}
