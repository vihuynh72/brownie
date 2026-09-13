package io.github.vihuynh72.brownie.core.revision;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists scoped patch proposals. Every method takes {@code userId}
 * alongside {@code workspaceId}, the same tenant-context requirement
 * {@code QuestionRepository} already carries.
 */
public interface PatchProposalRepository {

    PatchProposal create(
            long workspaceId,
            long userId,
            long documentId,
            long baseRevisionId,
            Map<String, FieldValue> proposedValues,
            Map<String, List<Long>> proposedEvidence);

    Optional<PatchProposal> find(long workspaceId, long userId, long documentId, long proposalId);

    /** Marks exactly one PROPOSED proposal ACCEPTED. Throws if it does not exist, belongs to another workspace, or is already accepted. */
    void markAccepted(long workspaceId, long userId, long documentId, long proposalId);
}
