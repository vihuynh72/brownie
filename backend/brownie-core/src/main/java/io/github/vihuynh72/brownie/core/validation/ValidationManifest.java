package io.github.vihuynh72.brownie.core.validation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * One complete, independent run of every validation layer against one
 * exact document revision: the freshly filled DOCX this run produced and
 * verified, the PDF rendered from it once a later task starts rendering
 * one (null until then -- a render is not required to report the
 * non-layout findings below), and every finding every layer produced.
 * Export approval binds to this manifest's own {@code id}, not to the
 * revision alone, so a later, different validation run of the same
 * revision id can never be silently substituted for the one actually
 * reviewed.
 */
public record ValidationManifest(
        long id,
        long workspaceId,
        long documentId,
        long revisionId,
        long templateId,
        long templateVersionId,
        long docxArtifactId,
        String docxSha256,
        Long pdfArtifactId,
        String pdfSha256,
        List<ValidationFinding> findings,
        OffsetDateTime createdAt) {

    public ValidationManifest {
        Objects.requireNonNull(docxSha256, "docxSha256");
        if ((pdfArtifactId == null) != (pdfSha256 == null)) {
            throw new IllegalArgumentException("pdfArtifactId and pdfSha256 must be both present or both absent.");
        }
        findings = List.copyOf(findings);
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean hasUnresolvedBlocking() {
        return findings.stream().anyMatch(finding -> finding.severity() == ValidationSeverity.BLOCKING);
    }
}
