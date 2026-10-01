package io.github.vihuynh72.brownie.core.document;

/**
 * How text written into a box on a PDF page looks: the family, whether it
 * is bold, and the size in points it starts at (a box that lets its text
 * shrink may end up smaller).
 */
public record PdfTextStyle(PdfFontFamily family, boolean bold, double sizePt) {

    /** What a box starts with when nothing on the page says otherwise: an ordinary sans-serif at a reading size. */
    public static final PdfTextStyle DEFAULT = new PdfTextStyle(PdfFontFamily.SANS, false, 11);

    public PdfTextStyle {
        if (family == null) {
            throw new IllegalArgumentException("A text style needs a font family.");
        }
        if (!(sizePt > 0) || Double.isInfinite(sizePt)) {
            throw new IllegalArgumentException("A text style's size must be a positive number of points.");
        }
    }
}
