package io.github.vihuynh72.brownie.core.export;

import java.util.Optional;

/** Tenant-scoped persistence for a document's export receipts. */
public interface ExportRepository {

    ExportReceipt save(
            long workspaceId,
            long userId,
            long documentId,
            long revisionId,
            long templateVersionId,
            long exportApprovalId,
            long validationManifestId,
            long docxArtifactId,
            String docxSha256,
            Long pdfArtifactId,
            String pdfSha256,
            ExportFormat format);

    Optional<ExportReceipt> findLatest(long workspaceId, long userId, long documentId);
}
