package io.github.vihuynh72.brownie.core.document;

/**
 * Where one value goes in a PDF: into one of the form's own text fields,
 * named by its full dotted name, or into a box on a page, where Brownie
 * draws the text itself.
 */
public sealed interface PdfFillTarget {

    record Widget(String fullName) implements PdfFillTarget {

        public Widget {
            if (fullName == null || fullName.isEmpty()) {
                throw new IllegalArgumentException("A field target needs the field's full name.");
            }
        }
    }

    /**
     * A box on a page ({@link PdfRect} convention). The text is drawn in
     * {@code style}, clipped to the box, upright as the page is shown. It
     * wraps onto more lines when {@code multiline} is true or the box is at
     * least two lines tall; otherwise it is one line, centred vertically.
     */
    record Box(int pageNumber, PdfRect box, PdfTextStyle style, boolean multiline) implements PdfFillTarget {

        public Box {
            if (pageNumber < 1 || box == null || style == null) {
                throw new IllegalArgumentException("A box target needs a page number, a box and a text style.");
            }
        }
    }
}
