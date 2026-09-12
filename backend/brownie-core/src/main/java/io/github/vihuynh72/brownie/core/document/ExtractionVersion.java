package io.github.vihuynh72.brownie.core.document;

import java.time.OffsetDateTime;

/**
 * One immutable extraction attempt against one artifact under one parser
 * version. COMPLETE carries a graph and an empty feature report;
 * UNSUPPORTED carries a non-empty feature report and no graph; FAILED
 * carries neither, only {@code failureReason}. Extraction is a pure
 * function of an artifact's immutable bytes and the extractor's own
 * version, so exactly one row ever exists per (artifact, parserVersion)
 * pair: extracting again under the same parser version returns this same
 * row rather than re-parsing, and a parser version change produces a new
 * row rather than replacing this one.
 */
public record ExtractionVersion(
        long id,
        long workspaceId,
        long artifactId,
        String parserVersion,
        ExtractionStatus status,
        DocxFeatureReport featureReport,
        DocxStructuralGraph graph,
        String failureReason,
        OffsetDateTime createdAt) {
}
