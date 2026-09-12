package io.github.vihuynh72.brownie.core.revision;

import java.util.Objects;

/**
 * A bounded field command. It is deliberately not a general JSON patch:
 * callers can set one typed field value or remove one known field value.
 */
public sealed interface DocumentFieldEdit permits DocumentFieldEdit.SetValue, DocumentFieldEdit.ClearValue {

    String fieldId();

    record SetValue(String fieldId, FieldValue value) implements DocumentFieldEdit {

        public SetValue {
            requireFieldId(fieldId);
            Objects.requireNonNull(value, "value");
        }
    }

    record ClearValue(String fieldId) implements DocumentFieldEdit {

        public ClearValue {
            requireFieldId(fieldId);
        }
    }

    private static void requireFieldId(String fieldId) {
        if (fieldId == null || fieldId.isBlank()) {
            throw new IllegalArgumentException("Field ID must not be blank.");
        }
    }
}
