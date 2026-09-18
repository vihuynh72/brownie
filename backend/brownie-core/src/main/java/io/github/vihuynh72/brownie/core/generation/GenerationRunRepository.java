package io.github.vihuynh72.brownie.core.generation;

import java.util.List;
import java.util.Optional;

/**
 * Persists generation runs. Every method takes {@code userId} alongside
 * {@code workspaceId}: a real implementation sets it as this transaction's
 * tenant-context row-level-security actor before touching the table, the
 * same requirement every other repository in this codebase carries.
 */
public interface GenerationRunRepository {

    /**
     * Records the run for a job, or returns the run that job already has:
     * a start request replayed under the same idempotency key resolves to
     * the same job, and one job is ever one run.
     */
    GenerationRun record(long workspaceId, long userId, NewGenerationRun run);

    Optional<GenerationRun> findByJob(long workspaceId, long userId, long jobId);

    /** Every run started for this document, most recent first. */
    List<GenerationRun> findForDocument(long workspaceId, long userId, long documentId);

    /** What {@link #record} needs; the row's own id, actor and timestamp are assigned when it is written. */
    record NewGenerationRun(
            long documentId,
            long baseRevisionId,
            long templateVersionId,
            long sourceSnapshotId,
            long jobId,
            String bundleHash,
            String modelName,
            String promptVersion) {
    }
}
