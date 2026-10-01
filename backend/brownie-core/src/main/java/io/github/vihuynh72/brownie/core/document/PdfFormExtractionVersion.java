package io.github.vihuynh72.brownie.core.document;

import java.time.OffsetDateTime;

/**
 * One reading of one PDF as a form, under one reader version, kept once
 * and never changed: the counterpart for PDF forms of {@link
 * ExtractionVersion}. A PDF template version is pinned to one of these the
 * way a Word one is pinned to its structural graph, so its places always
 * mean what they meant when they were checked. {@code status} is {@link
 * ExtractionStatus#COMPLETE} with {@code graph} set, or {@link
 * ExtractionStatus#UNSUPPORTED} with {@code unsupportedReason} saying why
 * the whole file cannot be filled; a reading never fails any other way.
 */
public record PdfFormExtractionVersion(
        long id,
        long workspaceId,
        long artifactId,
        String parserVersion,
        ExtractionStatus status,
        UnsupportedPdfFormReason unsupportedReason,
        String unsupportedDetail,
        PdfFormGraph graph,
        OffsetDateTime createdAt) {
}
