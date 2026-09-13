package io.github.vihuynh72.brownie.core.revision;

import java.util.Objects;

/**
 * The five independent dimensions one field or repeated item carries at
 * once: who or what produced it, whether it is supported by evidence,
 * whether it has passed validation, whether a person has reviewed it, and
 * whether it is protected from automated change. These are deliberately
 * not folded into one status enum -- a field can be AI-composed, directly
 * supported, unreviewed, and locked simultaneously, and a user's own edit
 * changes authorship without automatically implying anything about the
 * other four.
 */
public record FieldState(
        Authorship authorship, EvidenceSupport evidenceSupport, ValidationState validation, ReviewState review, LockState lock) {

    public FieldState {
        Objects.requireNonNull(authorship, "authorship");
        Objects.requireNonNull(evidenceSupport, "evidenceSupport");
        Objects.requireNonNull(validation, "validation");
        Objects.requireNonNull(review, "review");
        Objects.requireNonNull(lock, "lock");
    }

    /** The state a field gets the moment a person sets or replaces its value directly -- unreviewed, unvalidated, and never locked by that act alone. */
    public static FieldState freshlyUserAuthored(boolean hasEvidence) {
        return new FieldState(
                Authorship.USER_AUTHORED,
                hasEvidence ? EvidenceSupport.DIRECT : EvidenceSupport.MISSING,
                ValidationState.NOT_RUN,
                ReviewState.UNREVIEWED,
                LockState.EDITABLE);
    }
}
