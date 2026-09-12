package io.github.vihuynh72.brownie.core.document;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads real DOCX bytes and either returns a complete structural graph or
 * reports the specific unsupported features that stopped it. {@code
 * content} is not closed by an implementation; the caller owns its
 * lifecycle. Throws {@link DocxParseException}, not {@link IOException},
 * when the bytes cannot be parsed as a DOCX at all -- {@code IOException}
 * is reserved for a genuine failure reading the stream itself.
 */
public interface DocxStructuralExtractor {

    /**
     * Identifies both this extractor's own graph shape and the underlying
     * library version it depends on, so either one changing produces a new
     * {@link ExtractionVersion} rather than silently reinterpreting
     * evidence built against the old one.
     */
    String parserVersion();

    DocxExtractionOutcome extract(InputStream content) throws IOException;
}
