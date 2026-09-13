package io.github.vihuynh72.brownie.core.generation;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One proposed row of a template's repeated field group (today: one action
 * item), carrying a {@link FieldCandidate} per repeated field ID rather
 * than one shared citation for the whole group -- an owner and a due date
 * are different claims with different, possibly independent, evidence,
 * and one of them being unresolved must never silently blank or drop the
 * others. {@code fields} always has exactly one entry per repeated field
 * ID this extraction was asked about, by construction (see {@link
 * ExtractionPromptBuilder}), so item alignment across fields can never
 * drift the way independently generated per-field lists could.
 */
public record RepeatedItemCandidate(Map<String, FieldCandidate> fields) {

    public RepeatedItemCandidate {
        Objects.requireNonNull(fields, "fields");
        fields = Map.copyOf(fields);
    }

    public FieldCandidate get(String fieldId) {
        FieldCandidate candidate = fields.get(fieldId);
        if (candidate == null) {
            throw new IllegalArgumentException("No candidate for repeated field " + fieldId + " in this item.");
        }
        return candidate;
    }

    public List<String> fieldIds() {
        return List.copyOf(fields.keySet());
    }
}
