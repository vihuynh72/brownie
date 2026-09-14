package io.github.vihuynh72.brownie.core.validation;

/**
 * Rasterizes two PDFs at the same fixed resolution and reports a coarse
 * per-page pixel-difference signal. A real implementation is supplied by
 * whichever module wires this up, the same dependency-inversion shape
 * {@code io.github.vihuynh72.brownie.core.compile.DocumentRenderer}
 * already uses.
 */
public interface PageRasterDiffer {

    PageRasterComparison compare(byte[] baselinePdfBytes, byte[] filledPdfBytes);
}
