package io.github.vihuynh72.brownie.core.mcp;

import java.util.Optional;

/**
 * The durable record of which proposal a finished run was applied as.
 * One row per run is what makes "apply exactly once" survive a restart:
 * without it a second status call would start a second apply.
 */
public interface McpAppliedRunRepository {

    /** The proposal this run was applied as, or empty when no apply has been recorded for it. */
    Optional<Long> findProposal(long workspaceId, long userId, long runId);

    /**
     * Remembers the proposal a run was just applied as. The (workspace,
     * run) pair is unique, so a raced second apply loses its insert and
     * reads back the winner's proposal instead.
     */
    void record(long workspaceId, long userId, long runId, long documentId, long proposalId);
}
