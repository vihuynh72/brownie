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
}
