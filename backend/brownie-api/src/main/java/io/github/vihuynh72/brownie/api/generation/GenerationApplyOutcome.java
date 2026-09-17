package io.github.vihuynh72.brownie.api.generation;

import io.github.vihuynh72.brownie.core.revision.PatchProposal;

import java.util.List;
import java.util.Objects;

/**
 * What turning a finished job's published result into a proposal actually
 * produced: the real, persisted {@link PatchProposal}, plus an honest
 * account of every repeated row (an action item, in the built-in minutes
 * templates) the result contained but the proposal could not carry. A row
 * is only ever proposed whole -- the document's own content model stores a
 * repeated group as parallel, equal-length lists with no slot for "unknown",
 * so a row whose owner or due date the model could not determine cannot be
 * represented yet. Rather than drop such a row silently, which would let an
 * exported document claim "no action items recorded" over a transcript
 * that plainly contained some, it is named here so the person reviewing
 * the proposal can see exactly what still needs their own hand.
 */
public record GenerationApplyOutcome(
        PatchProposal proposal, int proposedRepeatedItemCount, List<SkippedRepeatedItem> skippedRepeatedItems) {

    public GenerationApplyOutcome {
        Objects.requireNonNull(proposal, "proposal");
        skippedRepeatedItems = List.copyOf(Objects.requireNonNullElse(skippedRepeatedItems, List.of()));
    }

    /**
     * One repeated row the result offered but the proposal left out.
     * {@code itemIndex} is the row's zero-based position in the result;
     * {@code unresolvedFieldIds} names exactly which of the row's fields
     * had no usable value; {@code description} is the row's first usable
     * text (typically the task itself), so a person can recognise which
     * row is meant without reading the raw result.
     */
    public record SkippedRepeatedItem(int itemIndex, List<String> unresolvedFieldIds, String description) {

        public SkippedRepeatedItem {
            unresolvedFieldIds = List.copyOf(Objects.requireNonNull(unresolvedFieldIds, "unresolvedFieldIds"));
        }
    }
}
