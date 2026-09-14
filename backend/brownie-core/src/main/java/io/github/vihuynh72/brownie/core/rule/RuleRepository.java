package io.github.vihuynh72.brownie.core.rule;

import java.util.List;
import java.util.Optional;

/** Every method takes workspace and user context explicitly rather than a bare rule ID, the same tenant-scoped pattern {@code TemplateRepository} follows. */
public interface RuleRepository {

    RuleRevision propose(
            long workspaceId,
            long userId,
            long templateId,
            long templateVersionId,
            RuleScope scope,
            RulePayload payload,
            String schemaVersion,
            String humanExplanation);

    Optional<RuleRevision> find(long workspaceId, long userId, long templateId, long ruleId);

    /** Every rule revision proposed against one template version, in the order proposed -- includes every status, not only PROPOSED, once a later task starts producing others. */
    List<RuleRevision> findByTemplateVersion(long workspaceId, long userId, long templateVersionId);

    /** Records which attached examples supported or contradicted one already-proposed rule -- written once, immediately after proposing it, never updated afterward. */
    void recordProposalEvidence(long workspaceId, long userId, long ruleId, RuleProposalEvidence evidence);

    /** The evidence recorded for one rule, if any was ever recorded -- empty for a manually proposed rule. */
    Optional<RuleProposalEvidence> findProposalEvidence(long workspaceId, long userId, long ruleId);

    /**
     * Moves one rule from {@code PROPOSED} to {@code decision} ({@code
     * ACCEPTED} or {@code REJECTED}) -- the only transition this codebase
     * allows; a rule already decided cannot be re-decided.
     *
     * @throws RuleDecisionConflictException if the rule does not exist or is not currently {@code PROPOSED}
     */
    RuleRevision decide(long workspaceId, long userId, long templateId, long ruleId, RuleRevisionStatus decision);
}
