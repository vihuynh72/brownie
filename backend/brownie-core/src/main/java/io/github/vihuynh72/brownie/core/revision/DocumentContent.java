package io.github.vihuynh72.brownie.core.revision;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The bounded editable content of one document revision. Keys are stable
 * template field IDs and values are the closed {@link FieldValue} variants.
 */
public record DocumentContent(Map<String, FieldValue> fields) {

    public DocumentContent {
        Objects.requireNonNull(fields, "fields");
        Map<String, FieldValue> copied = new LinkedHashMap<>();
        for (Map.Entry<String, FieldValue> entry : fields.entrySet()) {
            String fieldId = entry.getKey();
            if (fieldId == null || fieldId.isBlank()) {
                throw new IllegalArgumentException("Document content field IDs must not be blank.");
            }
            copied.put(fieldId, Objects.requireNonNull(entry.getValue(), "field value"));
        }
        fields = Map.copyOf(copied);
    }

    public static DocumentContent empty() {
        return new DocumentContent(Map.of());
    }
}
