package io.github.vihuynh72.brownie.core.document;

import java.util.Optional;

/** Same tenant-scoped, insert-or-return-existing pattern as {@link ExtractionVersionRepository}. */
public interface PdfExtractionVersionRepository {

    Optional<PdfExtractionVersion> findByArtifact(long workspaceId, long userId, long artifactId, String parserVersion);

    PdfExtractionVersion saveComplete(long workspaceId, long userId, long artifactId, String parserVersion, PdfStructuralGraph graph);

    PdfExtractionVersion saveUnsupported(
            long workspaceId, long userId, long artifactId, String parserVersion, UnsupportedPdfReason reason, String detail);

    PdfExtractionVersion saveFailed(long workspaceId, long userId, long artifactId, String parserVersion, String failureReason);
}
