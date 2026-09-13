package io.github.vihuynh72.brownie.core.generation;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The complete, validated outcome of one extraction call: one {@link
 * FieldCandidate} per scalar field this run asked about, and zero or more
 * {@link RepeatedItemCandidate} rows for the template's repeated group.
 * Every evidence span ID anywhere in this result has already been checked
 * against the exact set of spans this run actually offered the model --
 * see {@link ExtractionService} -- so nothing downstream needs to
 * re-validate a citation to trust it.
 */
public record ExtractionResult(Map<String, FieldCandidate> scalarCandidates, List<RepeatedItemCandidate> repeatedItems) {

    public ExtractionResult {
        Objects.requireNonNull(scalarCandidates, "scalarCandidates");
        Objects.requireNonNull(repeatedItems, "repeatedItems");
        scalarCandidates = Map.copyOf(scalarCandidates);
        repeatedItems = List.copyOf(repeatedItems);
    }
}
