package io.github.vihuynh72.brownie.core.prepare;

import java.io.IOException;

/**
 * One page of a scanned form as a JPEG the vision model can look at: rendered
 * small enough to read well (about 110 DPI, the long side at most 1,600 px),
 * plus the pixels so a drawn line under an answer can be found in them.
 */
public interface ScanPageRasterizer {

    /** The page as JPEG bytes and the grayscale pixels the same render produced. */
    PageJpeg jpegOf(byte[] pdf, int pageIndex) throws IOException;

    /** One rendered page: its JPEG bytes, its pixel width and height, and its pixels row-major. */
    record PageJpeg(byte[] jpeg, int widthPx, int heightPx, byte[] pixels) {}
}
