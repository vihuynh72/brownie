package io.github.vihuynh72.brownie.core.generation;

import java.util.List;
import java.util.Objects;

/**
 * One model-proposed value for one field (a scalar field, or one item's
 * slot within a repeated group -- see {@link RepeatedItemCandidate}).
 * {@code evidenceSpanIds} names which of the source spans actually offered
 * to the model it cited for this exact value; a value with no supporting
 * span is still a candidate, just an unsupported one -- see {@link
 * #evidenceSpanIds()}. {@code unresolved} is the model's own admission
 * that it could not confidently determine this field at all, distinct
 * from a resolved value that simply has no evidence: an unresolved
 * candidate's {@code value} is not a guess to fall back on.
 */
public record FieldCandidate(String fieldId, String value, List<Long> evidenceSpanIds, boolean unresolved, String ambiguityReason) {

    public FieldCandidate {
        Objects.requireNonNull(fieldId, "fieldId");
        if (fieldId.isBlank()) {
            throw new IllegalArgumentException("fieldId must not be blank");
        }
        evidenceSpanIds = List.copyOf(evidenceSpanIds);
        if (unresolved && value != null) {
            throw new IllegalArgumentException("An unresolved candidate for " + fieldId + " must not also carry a value.");
        }
        if (!unresolved && (ambiguityReason != null)) {
            throw new IllegalArgumentException("A resolved candidate for " + fieldId + " must not carry an ambiguity reason.");
        }
        if (unresolved && (ambiguityReason == null || ambiguityReason.isBlank())) {
            throw new IllegalArgumentException("An unresolved candidate for " + fieldId + " must state why.");
        }
    }
}
