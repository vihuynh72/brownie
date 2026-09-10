package io.github.vihuynh72.brownie.core.document;

import java.time.OffsetDateTime;

/**
 * The plain-text analog of {@link ExtractionVersion}/{@link
 * PdfExtractionVersion}. There is no "unsupported" case for plain text --
 * decoding either succeeds (COMPLETE) or the bytes are not well-formed
 * UTF-8 (FAILED) -- so {@link ExtractionStatus#UNSUPPORTED} is never
 * produced here, though the shared enum still permits it structurally.
 */
public record PlainTextExtractionVersion(
        long id,
        long workspaceId,
        long artifactId,
        String parserVersion,
        ExtractionStatus status,
        PlainTextStructuralGraph graph,
        String failureReason,
        OffsetDateTime createdAt) {
}
