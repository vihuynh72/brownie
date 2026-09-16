package io.github.vihuynh72.brownie.core.export;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * A person's own decision to approve one exact, already-validated revision
 * for export -- bound to the document revision, template version, and
 * validation manifest it was granted against; changing any of those
 * invalidates the approval. Approving again always appends a new row
 * (this codebase's established immutability discipline for every
 * revision-shaped record); {@code
 * findLatest} names the current one. An approval is current only while
 * the document's own current revision still equals {@link #revisionId()}
 * -- {@link ExportService} checks that at both approval and export time,
 * never trusting an old approval's own once-true claim.
 */
public record ExportApproval(
        long id,
        long workspaceId,
        long documentId,
        long revisionId,
        long templateVersionId,
        long validationManifestId,
        ExportFormat format,
        long actorUserId,
        OffsetDateTime approvedAt) {

    public ExportApproval {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(approvedAt, "approvedAt");
    }
}
