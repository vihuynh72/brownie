package io.github.vihuynh72.brownie.core.document;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads real PDF bytes and either returns per-page geometry and text or
 * reports why nothing useful could be extracted at all. {@code content} is
 * not closed by an implementation; the caller owns its lifecycle. Throws
 * {@link PdfParseException}, not {@link IOException}, when the bytes
 * cannot be parsed as a PDF at all -- {@code IOException} is reserved for
 * a genuine failure reading the stream itself.
 */
public interface PdfStructuralExtractor {

    /** Identifies both this extractor's own graph shape and the underlying library version it depends on. */
    String parserVersion();

    PdfExtractionOutcome extract(InputStream content) throws IOException;
}
