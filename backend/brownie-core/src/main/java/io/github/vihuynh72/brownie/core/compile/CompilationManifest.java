package io.github.vihuynh72.brownie.core.compile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * One exact record of compiling a document revision: which template
 * version's bindings were used, the resulting DOCX/PDF artifacts and their
 * hashes, the renderer that produced the PDF, and the independent
 * content-integrity findings. This is this phase's own bounded receipt --
 * not the later, fuller export receipt this plan describes (bound to a
 * human review decision and a validation manifest, neither of which exist
 * yet), so it is named for what it actually is rather than borrowing that
 * later name.
 */
public record CompilationManifest(
        long id,
        long workspaceId,
        long documentId,
        long revisionId,
        long templateId,
        long templateVersionId,
        long docxArtifactId,
        String docxSha256,
        long pdfArtifactId,
        String pdfSha256,
        String rendererVersion,
        List<IntegrityFinding> integrityFindings,
        OffsetDateTime compiledAt) {

    public CompilationManifest {
        Objects.requireNonNull(docxSha256, "docxSha256");
        Objects.requireNonNull(pdfSha256, "pdfSha256");
        Objects.requireNonNull(rendererVersion, "rendererVersion");
        integrityFindings = List.copyOf(integrityFindings);
        Objects.requireNonNull(compiledAt, "compiledAt");
    }

    public boolean allIntegrityChecksPassed() {
        return integrityFindings.stream().allMatch(IntegrityFinding::passed);
    }
}
