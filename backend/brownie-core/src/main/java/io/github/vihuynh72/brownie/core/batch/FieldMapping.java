package io.github.vihuynh72.brownie.core.batch;

import java.util.Objects;

/** One line of a batch's mapping: the field, the 0-based column it fills from, and how. */
public record FieldMapping(String fieldId, int column, FieldMappingMode mode) {

    public FieldMapping {
        Objects.requireNonNull(fieldId, "fieldId must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        if (column < 0) {
            throw new IllegalArgumentException("A mapping's column is never negative.");
        }
    }
}
