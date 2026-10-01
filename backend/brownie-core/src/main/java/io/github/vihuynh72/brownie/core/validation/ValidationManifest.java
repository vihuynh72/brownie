package io.github.vihuynh72.brownie.core.validation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * One complete, independent run of every validation layer against one
 * exact document revision: the freshly filled DOCX this run produced and
 * verified, the PDF rendered from it when a render ran
 * (null otherwise -- a render is not required to report the
 * non-layout findings below), and every finding every layer produced.
 * Export approval binds to this manifest's own {@code id}, not to the
 * revision alone, so a later, different validation run of the same
 * revision id can never be silently substituted for the one actually
 * reviewed. A PDF template's run fills a PDF and nothing else, so its
 * {@code docxArtifactId}/{@code docxSha256} are null and its PDF is
 * always there.
 */
public record ValidationManifest(
        long id,
        long workspaceId,
        long documentId,
        long revisionId,
        long templateId,
        long templateVersionId,
        Long docxArtifactId,
        String docxSha256,
        Long pdfArtifactId,
        String pdfSha256,
        List<ValidationFinding> findings,
        OffsetDateTime createdAt) {

    public ValidationManifest {
        if ((docxArtifactId == null) != (docxSha256 == null)) {
            throw new IllegalArgumentException("docxArtifactId and docxSha256 must be both present or both absent.");
        }
        if ((pdfArtifactId == null) != (pdfSha256 == null)) {
            throw new IllegalArgumentException("pdfArtifactId and pdfSha256 must be both present or both absent.");
        }
        if (docxArtifactId == null && pdfArtifactId == null) {
            throw new IllegalArgumentException("A validation manifest names at least one file.");
        }
        findings = List.copyOf(findings);
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean hasUnresolvedBlocking() {
        return findings.stream().anyMatch(finding -> finding.severity() == ValidationSeverity.BLOCKING);
    }
}
