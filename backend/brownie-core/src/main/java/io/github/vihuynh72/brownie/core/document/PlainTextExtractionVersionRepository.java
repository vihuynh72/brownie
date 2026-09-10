package io.github.vihuynh72.brownie.core.document;

import java.util.Optional;

/** Same tenant-scoped, insert-or-return-existing pattern as {@link ExtractionVersionRepository}/{@link PdfExtractionVersionRepository}. */
public interface PlainTextExtractionVersionRepository {

    Optional<PlainTextExtractionVersion> findByArtifact(long workspaceId, long userId, long artifactId, String parserVersion);

    PlainTextExtractionVersion saveComplete(
            long workspaceId, long userId, long artifactId, String parserVersion, PlainTextStructuralGraph graph);

    PlainTextExtractionVersion saveFailed(long workspaceId, long userId, long artifactId, String parserVersion, String failureReason);
}
