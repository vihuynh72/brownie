package io.github.vihuynh72.brownie.core.document;

import java.util.Objects;

/**
 * What running the extractor against real bytes actually produced -- a
 * complete graph, or the specific reasons the document falls outside the
 * qualified subset. Never a partial graph silently missing the parts it
 * could not handle.
 */
public sealed interface DocxExtractionOutcome {

    /**
     * A complete graph, with what the file keeps as it is ({@link
     * DocxFeatureReport#keptAsIs()}): the things that do not affect filling
     * and that a filled copy may carry only if its template does.
     */
    record Supported(DocxStructuralGraph graph, DocxFeatureReport keptAsIs) implements DocxExtractionOutcome {

        public Supported {
            Objects.requireNonNull(keptAsIs, "keptAsIs");
        }

        /** A document with nothing kept as is. */
        public Supported(DocxStructuralGraph graph) {
            this(graph, DocxFeatureReport.empty());
        }
    }

    /** Carries every finding, the ones kept as they are included, so the reasons are seen in context. */
    record Unsupported(DocxFeatureReport featureReport) implements DocxExtractionOutcome {
    }
}
