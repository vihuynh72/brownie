package io.github.vihuynh72.brownie.core.document;

/**
 * What reading a PDF as a form produced: the form's graph, or the one
 * reason the whole file cannot be filled in. A scan with no text is
 * {@link Supported}, with every page's {@code hasText} false.
 */
public sealed interface PdfFormReading {

    record Supported(PdfFormGraph graph) implements PdfFormReading {
    }

    record Unsupported(UnsupportedPdfFormReason reason, String detail) implements PdfFormReading {
    }
}
