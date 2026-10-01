package io.github.vihuynh72.brownie.core.document;

/**
 * Reads real PDF bytes as a form to fill in: pages, words with their fonts,
 * drawn lines, boxes and pictures, fillable fields and their widgets, and
 * what the file would do on its own. It never throws for a file it cannot
 * use; it says why in {@link PdfFormReading.Unsupported}. The text reader
 * for PDFs used as sources is a separate thing with its own version and
 * cache, and reading a form does not change it.
 */
public interface PdfFormReader {

    /** Names both the shape of the graph and the library version it depends on. */
    String parserVersion();

    PdfFormReading read(byte[] pdf);
}
