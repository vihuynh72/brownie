package io.github.vihuynh72.brownie.core.rule;

import java.util.List;

/**
 * Which attached examples actually back one proposed rule, and which
 * disagreed with it -- the "record support count and contradiction count"
 * evidence {@link RuleRevision}'s own javadoc already named as belonging to
 * "an example-driven teaching flow that does not exist yet." A manually
 * proposed rule (through {@link RuleService#propose}) carries no evidence
 * at all; only a rule proposed by comparing a template's attached examples
 * ({@code ExampleRuleProposer}, {@code core.example}) ever produces one.
 * Each ID here names one attached {@code TemplateExample} -- the "location"
 * this evidence points to, since an example is itself the whole unit of
 * evidence being compared, not a smaller excerpt within it.
 */
public record RuleProposalEvidence(List<Long> supportingExampleIds, List<Long> contradictingExampleIds) {

    public RuleProposalEvidence {
        supportingExampleIds = List.copyOf(supportingExampleIds);
        contradictingExampleIds = List.copyOf(contradictingExampleIds);
    }

    public int supportingExampleCount() {
        return supportingExampleIds.size();
    }

    public int contradictingExampleCount() {
        return contradictingExampleIds.size();
    }
}
