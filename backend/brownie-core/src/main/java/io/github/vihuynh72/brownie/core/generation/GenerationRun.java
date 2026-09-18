package io.github.vihuynh72.brownie.core.generation;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * The owned record of one generation started for one document: which
 * revision was current, which source fed it (and that source's artifact,
 * joined in so a caller never has to look it up separately), which
 * durable job carries it, and which model and prompt version it used.
 * Immutable once written; the job row it names is where the run's
 * progress lives, and its questions reference this row.
 */
public record GenerationRun(
        long id,
        long workspaceId,
        long documentId,
        long baseRevisionId,
        long templateVersionId,
        long sourceSnapshotId,
        long sourceArtifactId,
        long jobId,
        String bundleHash,
        String modelName,
        String promptVersion,
        long requestedByUserId,
        OffsetDateTime createdAt) {

    public GenerationRun {
        Objects.requireNonNull(bundleHash, "bundleHash");
        Objects.requireNonNull(modelName, "modelName");
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
