package io.github.vihuynh72.brownie.core.validation;

import java.util.List;

/**
 * Rasterizes two PDFs at the same fixed resolution and reports a coarse
 * per-page pixel-difference signal. A real implementation is supplied by
 * whichever module wires this up, the same dependency-inversion shape
 * {@code io.github.vihuynh72.brownie.core.compile.DocumentRenderer}
 * already uses.
 */
public interface PageRasterDiffer {

    PageRasterComparison compare(byte[] baselinePdfBytes, byte[] filledPdfBytes);

    /**
     * Draws {@code referencePdfBytes} (the form as uploaded) and {@code
     * filledPdfBytes} and counts, page by page, the pixels that differ
     * outside {@code masks}. Unlike {@link #compare}, both files may have
     * come straight from a person's upload, so an implementation bounds
     * what drawing them may cost. A differ that only compares whole pages
     * does not offer this.
     */
    default MaskedRasterComparison compareMasked(byte[] referencePdfBytes, byte[] filledPdfBytes, List<RasterMask> masks) {
        throw new UnsupportedOperationException("This differ compares whole pages only.");
    }
}
