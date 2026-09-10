package io.github.vihuynh72.brownie.core.document;

/** The one format-agnostic answer {@link DocumentExtractionService#extract} returns, so a caller that does not care which format it was does not have to switch on it either. */
public sealed interface ExtractionResult {

    record Docx(ExtractionVersion version) implements ExtractionResult {
    }

    record Pdf(PdfExtractionVersion version) implements ExtractionResult {
    }

    record PlainText(PlainTextExtractionVersion version) implements ExtractionResult {
    }
}
