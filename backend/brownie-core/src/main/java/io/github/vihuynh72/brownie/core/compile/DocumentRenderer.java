package io.github.vihuynh72.brownie.core.compile;

/**
 * Converts a filled DOCX to PDF. A real implementation must run the
 * conversion isolated from the trusted process calling it -- see this
 * plan's own file-processing isolation contract -- and is supplied by
 * whichever module wires this up; this interface carries no isolation
 * mechanism of its own.
 */
public interface DocumentRenderer {

    RenderedPdf renderToPdf(byte[] docxBytes);
}
