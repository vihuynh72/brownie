package io.github.vihuynh72.brownie.core.batch;

import io.github.vihuynh72.brownie.core.export.ExportFormat;

import java.time.OffsetDateTime;

/**
 * One export run over a batch's documents, in one format. The run is made
 * whole with an item per row, then advanced in bounded steps; {@code
 * completedAt} is set exactly when its state becomes COMPLETE. Idempotent
 * per member and key, the same rule a batch itself follows.
 */
public record BatchExport(
        long id,
        long workspaceId,
        long batchId,
        ExportFormat format,
        BatchExportState state,
        String idempotencyKey,
        String requestHash,
        long createdByUserId,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt) {
}
