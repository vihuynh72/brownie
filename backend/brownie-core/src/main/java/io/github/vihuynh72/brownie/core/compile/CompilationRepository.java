package io.github.vihuynh72.brownie.core.compile;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped persistence for a document revision's compilation manifests. */
public interface CompilationRepository {

    CompilationManifest save(
            long workspaceId,
            long userId,
            long documentId,
            long revisionId,
            long templateId,
            long templateVersionId,
            long docxArtifactId,
            String docxSha256,
            long pdfArtifactId,
            String pdfSha256,
            String rendererVersion,
            List<IntegrityFinding> integrityFindings);

    Optional<CompilationManifest> findLatest(long workspaceId, long userId, long documentId, long revisionId);
}
