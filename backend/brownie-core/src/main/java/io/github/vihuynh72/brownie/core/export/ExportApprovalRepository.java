package io.github.vihuynh72.brownie.core.export;

import java.util.Optional;

/** Tenant-scoped persistence for a document's export approvals. */
public interface ExportApprovalRepository {

    ExportApproval save(
            long workspaceId,
            long userId,
            long documentId,
            long revisionId,
            long templateVersionId,
            long validationManifestId,
            ExportFormat format);

    Optional<ExportApproval> findLatest(long workspaceId, long userId, long documentId);
}
