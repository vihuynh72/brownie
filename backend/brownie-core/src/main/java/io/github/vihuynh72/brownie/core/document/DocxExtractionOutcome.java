package io.github.vihuynh72.brownie.core.document;

/**
 * What running the extractor against real bytes actually produced -- a
 * complete graph, or the specific reasons the document falls outside the
 * qualified subset. Never a partial graph silently missing the parts it
 * could not handle.
 */
public sealed interface DocxExtractionOutcome {

    record Supported(DocxStructuralGraph graph) implements DocxExtractionOutcome {
    }

    record Unsupported(DocxFeatureReport featureReport) implements DocxExtractionOutcome {
    }
}
