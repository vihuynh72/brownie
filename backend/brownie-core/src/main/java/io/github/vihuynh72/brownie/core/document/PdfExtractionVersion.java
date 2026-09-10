package io.github.vihuynh72.brownie.core.document;

import java.time.OffsetDateTime;

/**
 * One immutable extraction attempt against one PDF artifact under one
 * parser version -- the PDF analog of {@link ExtractionVersion}, kept as
 * its own type rather than folded into that one: the two formats' notions
 * of "what went wrong" are genuinely different shapes (DOCX rejects for a
 * list of located structural findings; a PDF is unsupported for one of two
 * whole-document reasons), and forcing them into one shared record would
 * mean nullable fields whose meaning depends on a format no shared type
 * would state directly. {@link ExtractionStatus} itself is reused as-is:
 * COMPLETE/UNSUPPORTED/FAILED mean the same thing for either format.
 */
public record PdfExtractionVersion(
        long id,
        long workspaceId,
        long artifactId,
        String parserVersion,
        ExtractionStatus status,
        UnsupportedPdfReason unsupportedReason,
        String unsupportedDetail,
        PdfStructuralGraph graph,
        String failureReason,
        OffsetDateTime createdAt) {
}
