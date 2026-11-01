package io.github.vihuynh72.brownie.core.prepare;

import java.io.IOException;

/**
 * A photographed or scanned paper form becomes the one-page PDF the rest of the
 * pipeline reads. Implemented where PDFBox lives (the API module).
 */
public interface ImageToPdf {

    /** The picture as a one-page PDF: the image drawn full-page at 150 DPI, upright the way EXIF says it was taken. */
    byte[] pdfOf(byte[] imageBytes) throws IOException;
}
