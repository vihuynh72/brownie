package io.github.vihuynh72.brownie.core.document;

/**
 * What running the PDF extractor against real bytes actually produced. A
 * per-page or per-line ambiguity (an unclear reading order, a page with no
 * text) is carried as ordinary data inside a {@link Supported} graph, not
 * as an {@link Unsupported} outcome -- a PDF is read as evidence, not
 * filled in as an editable template the way a DOCX is, so an uncertain
 * region is something to flag for a person to double-check, not a reason
 * to refuse the whole document. {@link Unsupported} is reserved for the
 * two cases nothing useful can be extracted at all: the document is
 * encrypted, or no page has any extractable text.
 */
public sealed interface PdfExtractionOutcome {

    record Supported(PdfStructuralGraph graph) implements PdfExtractionOutcome {
    }

    record Unsupported(UnsupportedPdfReason reason, String detail) implements PdfExtractionOutcome {
    }
}
