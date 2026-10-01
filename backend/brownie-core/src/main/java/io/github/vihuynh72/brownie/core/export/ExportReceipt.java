package io.github.vihuynh72.brownie.core.export;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * The final, durable record of one completed export transaction --
 * exactly which artifact bytes were shipped, for which approval, bound to
 * which validation manifest (and, through it, the exact revision and
 * template version it was compiled from). {@code pdfArtifactId}/{@code
 * pdfSha256} are null when the bound manifest itself never produced a PDF
 * -- a real, honestly-represented partial result, the same nullable-pair
 * shape {@link io.github.vihuynh72.brownie.core.validation.ValidationManifest}
 * already uses, never a claim that both formats succeeded when only one
 * did. {@code docxArtifactId}/{@code docxSha256} are null for a PDF
 * template's export, which is a PDF and nothing else.
 */
public record ExportReceipt(
        long id,
        long workspaceId,
        long documentId,
        long revisionId,
        long templateVersionId,
        long exportApprovalId,
        long validationManifestId,
        Long docxArtifactId,
        String docxSha256,
        Long pdfArtifactId,
        String pdfSha256,
        ExportFormat format,
        long actorUserId,
        OffsetDateTime exportedAt) {

    public ExportReceipt {
        if ((docxArtifactId == null) != (docxSha256 == null)) {
            throw new IllegalArgumentException("docxArtifactId and docxSha256 must be both present or both absent.");
        }
        if ((pdfArtifactId == null) != (pdfSha256 == null)) {
            throw new IllegalArgumentException("pdfArtifactId and pdfSha256 must be both present or both absent.");
        }
        if (docxArtifactId == null && pdfArtifactId == null) {
            throw new IllegalArgumentException("An export receipt names at least one file.");
        }
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(exportedAt, "exportedAt");
    }

    /** Whether the export holds both the Word file and the PDF. */
    public boolean isCompletePair() {
        return docxArtifactId != null && pdfArtifactId != null;
    }
}
