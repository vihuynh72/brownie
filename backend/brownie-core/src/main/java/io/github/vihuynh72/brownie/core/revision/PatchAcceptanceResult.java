package io.github.vihuynh72.brownie.core.revision;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of {@link RevisionService#acceptPatch}: the comparison that
 * decided which fields could apply, and the resulting mutation -- absent
 * when every field was blocked, since nothing was appended and the
 * proposal was not marked accepted in that case.
 */
public record PatchAcceptanceResult(PatchComparison comparison, Optional<DocumentMutationResult> mutation) {

    public PatchAcceptanceResult {
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(mutation, "mutation");
    }
}
