package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;

/**
 * Where a {@link FieldDefinition} actually points inside its template's
 * source. For a Word template: {@link ContentControlTag} is the
 * built-in-template convention this product picked (a stable named content
 * control -- see the DOCX-binding spike and {@code
 * PoiDocxStructuralExtractor}); {@link StructuralNode} is the more general
 * fallback for a custom template's detected node, scoped to one document
 * part because a bare {@code nodeId} (a sibling-index path like {@code
 * "p2/sdt0/r0"}) is only unique within the part it was extracted from, not
 * across the whole document. For a PDF template: {@link AcroFormField} is
 * one of the form's own fillable text fields, by its full dotted name, and
 * {@link PageBox} is a box on a page where Brownie draws the text itself.
 * A binding only ever means something in a template of its own {@link
 * #templateKind()}.
 */
public sealed interface FieldBindingTarget {

    /** The kind of template this binding can point into. */
    default TemplateKind templateKind() {
        return switch (this) {
            case ContentControlTag ignored -> TemplateKind.DOCX;
            case StructuralNode ignored -> TemplateKind.DOCX;
            case AcroFormField ignored -> TemplateKind.PDF;
            case PageBox ignored -> TemplateKind.PDF;
        };
    }

    record ContentControlTag(String tag) implements FieldBindingTarget {
    }

    record StructuralNode(DocumentPartKind part, String nodeId) implements FieldBindingTarget {
    }

    /** One of a PDF form's own text fields, by its full dotted name ("applicant.phone"). It is filled with text made smaller to fit when it must be. */
    record AcroFormField(String name) implements FieldBindingTarget {

        public AcroFormField {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("A PDF form field binding needs the field's full name.");
            }
        }
    }

    /**
     * A box on one page of a PDF, measured the way every PDF form record is
     * ({@link PdfRect}): points, the page as stored, origin at the crop
     * box's top-left, Y down. The text is drawn in {@code style}, on more
     * than one line when {@code multiline} is true, and {@code overflow}
     * says what happens when it does not fit: made smaller down to six
     * points ({@link PdfOverflowPolicy#SHRINK_TO_FIT}, what a box gets
     * unless the person chose otherwise), or reported so export stops
     * ({@link PdfOverflowPolicy#BLOCK}).
     */
    record PageBox(
            int page, double x, double y, double width, double height, PdfTextStyle style, boolean multiline, PdfOverflowPolicy overflow)
            implements FieldBindingTarget {

        public PageBox {
            if (page < 1 || style == null || overflow == null) {
                throw new IllegalArgumentException("A page box binding needs a page number, a text style and an overflow choice.");
            }
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height)) {
                throw new IllegalArgumentException("A page box binding's position and size must be numbers.");
            }
        }

        public PdfRect box() {
            return new PdfRect(x, y, width, height);
        }
    }
}
