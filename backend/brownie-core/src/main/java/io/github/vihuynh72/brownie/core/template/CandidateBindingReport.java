package io.github.vihuynh72.brownie.core.template;

import java.util.List;

/**
 * Everything {@link FieldBindingCandidateProposer} found in one structural
 * graph. {@code candidates} are usable as-is by {@link
 * TemplateService#replaceDraftBindings}; {@code ambiguousContentControlTags}
 * names a real content control tag the proposer deliberately did not turn
 * into a candidate because it appears at more than one location -- the same
 * "reject an ambiguous target" rule {@link TemplateBindingValidator} already
 * enforces at binding time, surfaced earlier so a person knows *why* a tag
 * they can see in the document did not get proposed, and that recovering it
 * needs an explicit {@link FieldBindingTarget.StructuralNode} mapping to one
 * exact occurrence instead.
 */
public record CandidateBindingReport(List<CandidateFieldBinding> candidates, List<String> ambiguousContentControlTags) {

    public CandidateBindingReport {
        candidates = List.copyOf(candidates);
        ambiguousContentControlTags = List.copyOf(ambiguousContentControlTags);
    }
}
