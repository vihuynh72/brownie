package io.github.vihuynh72.brownie.core.document;

/**
 * One line of text as this extractor grouped it, addressable by its {@code
 * lineIndex} within its {@link PdfPage}. {@code x}/{@code y} are the
 * bounding box's top-left corner, {@code width}/{@code height} its size --
 * all in the page's own native (unrotated) coordinate space, in PDF user-
 * space points, with the origin at the page's top-left corner and Y
 * increasing downward. That Y-down convention is a deliberate choice, not
 * raw PDF space (whose native origin is bottom-left with Y increasing
 * upward): it matches PDFBox's own {@code TextPosition} "direction
 * adjusted" coordinates directly, avoiding an extra transform, and reads
 * naturally for a top-to-bottom line-ordered model like this one.
 *
 * <p>{@code ambiguousReadingOrder} is true when this line's own text was
 * reconstructed from character positions with an unusually large
 * horizontal gap partway through it -- the standard, simple signal that
 * two visually separate regions (commonly side-by-side columns, or two
 * table cells with no ruled border) were merged into one line by this
 * extractor's own Y-position clustering, which has no real understanding
 * of columns or tables. A true value does not mean the line was dropped or
 * is wrong; it means a person should not trust its reading order blindly.
 */
public record PdfTextLine(int lineIndex, String text, double x, double y, double width, double height, boolean ambiguousReadingOrder) {
}
