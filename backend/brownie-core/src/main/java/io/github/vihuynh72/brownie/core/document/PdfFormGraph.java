package io.github.vihuynh72.brownie.core.document;

import java.util.List;

/**
 * What one PDF holds that matters for filling it in: each page's geometry,
 * its words with their fonts, the lines, boxes and pictures drawn on it,
 * the fillable fields it declares, and what it would do on its own if
 * opened. It is the PDF counterpart of the structural graph a Word form is
 * read into, and like that graph it names the exact reader that produced
 * it, so a later change to the reading is visible instead of silently
 * replacing what a template was built against.
 *
 * <p>Every rectangle and point is a {@link PdfRect} or {@link PdfPoint}:
 * points, the page as stored (before {@code /Rotate}), origin at the crop
 * box's top-left, Y down. A page with no text at all (a scan) is ordinary
 * data here, {@code hasText} false, not a reason to refuse the file: a
 * person can still draw boxes on it.
 */
public record PdfFormGraph(String parserVersion, List<Page> pages, AcroForm acroForm, Risks risks) {

    public PdfFormGraph {
        pages = List.copyOf(pages);
    }

    /**
     * One page. {@code pageNumber} is one-based. {@code rotation} is the
     * page's own {@code /Rotate} (0, 90, 180 or 270), kept as a fact and
     * never applied to the geometry. {@code userUnit} is the page's {@code
     * /UserUnit}, 1 unless the page declares otherwise; boxes are only drawn
     * on pages where it is 1.
     */
    public record Page(
            int pageNumber,
            CropBox cropBox,
            int rotation,
            double userUnit,
            boolean hasText,
            List<Line> lines,
            List<Rule> rules,
            List<PdfRect> rects,
            List<Image> images) {

        public Page {
            lines = List.copyOf(lines);
            rules = List.copyOf(rules);
            rects = List.copyOf(rects);
            images = List.copyOf(images);
        }
    }

    /** The crop box in the PDF's own user space: lower-left corner and size, in points. Every rectangle on the page is measured from its top-left. */
    public record CropBox(double llx, double lly, double width, double height) {
    }

    /**
     * One line of text, grouped exactly as the source text reader groups
     * lines, so {@code text} is the same string it would give. {@code index}
     * is the line's position on its page, top to bottom.
     */
    public record Line(int index, String text, PdfRect box, List<Word> words) {

        public Line {
            words = List.copyOf(words);
        }
    }

    /**
     * One word of a line. A run of underscores or dots (a blank to write on)
     * is always a word of its own, even when printed hard against a label.
     * {@code fontName} is the font's name with any subset prefix ({@code
     * ABCDEF+}) removed. {@code textDirection} is the direction the text
     * runs on the page as stored, in degrees counter-clockwise (0, 90, 180
     * or 270): text that reads upright on a page turned by {@code /Rotate}
     * runs in the page's own rotation.
     */
    public record Word(String text, PdfRect box, String fontName, double fontSizePt, int textDirection) {
    }

    /** A straight horizontal or vertical line segment the page strokes or fills, from one end to the other. */
    public record Rule(PdfPoint from, PdfPoint to) {

        public boolean horizontal() {
            return Math.abs(to.y() - from.y()) <= Math.abs(to.x() - from.x());
        }

        public double length() {
            return Math.hypot(to.x() - from.x(), to.y() - from.y());
        }
    }

    /**
     * Where a picture is drawn and which codecs its data declares ({@code
     * DCTDecode}, {@code JBIG2Decode}, ...). The picture itself is never
     * decoded while reading.
     */
    public record Image(PdfRect box, List<String> filters) {

        public Image {
            filters = List.copyOf(filters);
        }
    }

    /**
     * The fillable form the PDF declares, if any. {@code present} is false
     * when there is no form at all, in which case {@code fields} is empty.
     * {@code signedSignatureCount} counts signature fields that already hold
     * a signature.
     */
    public record AcroForm(boolean present, XfaKind xfa, boolean needAppearances, List<Field> fields, int signedSignatureCount) {

        public AcroForm {
            fields = List.copyOf(fields);
        }

        public static AcroForm absent() {
            return new AcroForm(false, XfaKind.NONE, false, List.of(), 0);
        }
    }

    /** Whether the form also carries an XFA description, and whether that description draws the pages itself (dynamic). */
    public enum XfaKind {
        NONE,
        STATIC,
        DYNAMIC
    }

    /**
     * One fillable field, by its full dotted name. {@code maxLen} is null
     * when the field sets no limit. {@code tooltip} is the field's own
     * description ({@code /TU}), null when absent. {@code dateFormat} is the
     * date format the field's own formatting script names ("mm/dd/yyyy"),
     * null when it has none; the script is recorded, never run. A field
     * none of whose widgets is on a page read here has no widgets.
     */
    public record Field(
            String fullName,
            FieldKind kind,
            boolean readOnly,
            boolean required,
            boolean multiline,
            boolean comb,
            Integer maxLen,
            String tooltip,
            String dateFormat,
            List<Widget> widgets) {

        public Field {
            widgets = List.copyOf(widgets);
        }
    }

    public enum FieldKind {
        TEXT,
        CHECKBOX,
        RADIO,
        CHOICE,
        SIGNATURE,
        BUTTON
    }

    /** One place a field is shown: the page it is on and its rectangle there. */
    public record Widget(int pageNumber, PdfRect box) {
    }

    /**
     * What the file would do on its own, found by looking and never by
     * running anything: scripts the document runs when it opens or a page is
     * shown, actions that start another program, files carried inside it.
     * A reading that returns a graph refused every file with any of these,
     * so in a graph they are all false; they are kept so a stored reading
     * says what was checked.
     */
    public record Risks(boolean documentJavaScript, boolean launchActions, boolean embeddedFiles) {

        public boolean any() {
            return documentJavaScript || launchActions || embeddedFiles;
        }
    }
}
