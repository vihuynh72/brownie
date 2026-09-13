package io.github.vihuynh72.brownie.core.generation;

import java.util.Objects;

/**
 * One accepted fact offered to a composition call: {@code fieldId}'s
 * current resolved value, rendered as plain text, together with the
 * single evidence span ID the model may cite back for it -- or {@code
 * null} when this fact is resolved but was never itself cited (for
 * example, a question a person answered by typing a value that matched
 * none of the offered candidate options; see {@code
 * AcceptedFactsReconciler}). A fact with no citable span is still real,
 * usable context; the model is simply told it cannot cite a span ID for
 * it, and a composed value drawing only on such facts is correctly
 * recorded with empty evidence rather than a fabricated citation -- the
 * same "a value with no supporting span is still a candidate, just an
 * unsupported one" allowance {@link FieldCandidate} already documents.
 *
 * <p>Only the first of a multi-span fact's own evidence span IDs is
 * offered here (see {@link CompositionService#toKnownFacts}): composition
 * cites at fact granularity, not raw span granularity, so one
 * representative citation per fact is enough.
 */
public record KnownFact(String fieldId, String text, Long citableSpanId) {

    public KnownFact {
        Objects.requireNonNull(fieldId, "fieldId");
        Objects.requireNonNull(text, "text");
        if (citableSpanId != null && citableSpanId <= 0) {
            throw new IllegalArgumentException("citableSpanId must be positive when present.");
        }
    }
}
