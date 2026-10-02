package io.github.vihuynh72.brownie.core.document;

import java.util.Optional;

/**
 * Tenant-scoped storage for PDF form readings, with the same
 * insert-or-return-existing rule as {@link ExtractionVersionRepository}:
 * reading a form depends only on the file's fixed bytes and the reader's
 * version, so two people preparing the same file at once end up with the
 * one row either of them wrote first.
 */
public interface PdfFormExtractionVersionRepository {

    Optional<PdfFormExtractionVersion> findById(long workspaceId, long userId, long id);

    Optional<PdfFormExtractionVersion> findByArtifact(long workspaceId, long userId, long artifactId, String parserVersion);

    /**
     * The id of the file's complete reading under {@code parserVersion}, if
     * there is one, without the reading itself: a reading can run to tens of
     * megabytes, and answering what the upload step kept needs only its id.
     */
    Optional<Long> findCompleteIdByArtifact(long workspaceId, long userId, long artifactId, String parserVersion);

    PdfFormExtractionVersion saveComplete(long workspaceId, long userId, long artifactId, String parserVersion, PdfFormGraph graph);

    PdfFormExtractionVersion saveUnsupported(
            long workspaceId, long userId, long artifactId, String parserVersion, UnsupportedPdfFormReason reason, String detail);
}
