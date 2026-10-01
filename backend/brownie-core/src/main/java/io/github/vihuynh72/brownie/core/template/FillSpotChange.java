package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
import io.github.vihuynh72.brownie.core.prepare.DocxAnchor;

import java.util.Objects;

/**
 * One correction a person makes to the fill spots of an open document. Each
 * one is made on a new template version derived from the document's own
 * ({@link TemplateDerivationService}), never on a version documents already
 * use. A spot is named by its field ID; a new one is given an ID made from
 * its label.
 *
 * <p>{@link Add} is a place in a Word form's text; {@link AddBox}, {@link
 * MoveBox} and {@link RestyleBox} are boxes on a PDF form's pages. {@link
 * Rename} and {@link Remove} work on either kind of form.
 */
public sealed interface FillSpotChange {

    /**
     * A new single-value spot at {@code anchor}, called {@code label}.
     * {@code placedByModel} is true when the place was chosen by the model
     * from the person's words rather than pointed at by the person, so the
     * spot shows as one Brownie found.
     */
    record Add(DocxAnchor anchor, String label, FieldType type, boolean placedByModel) implements FillSpotChange {
        public Add {
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(type, "type");
        }
    }

    /** A new name for an existing spot. Only the name changes: the ID, the value and where the spot is stay as they were. */
    record Rename(String fieldId, String label) implements FillSpotChange {
        public Rename {
            Objects.requireNonNull(fieldId, "fieldId");
            Objects.requireNonNull(label, "label");
        }
    }

    /**
     * Takes a single-value spot away, putting the form's own text back where
     * Brownie had made it a spot. On a PDF form only the spot goes: the file
     * is never changed.
     */
    record Remove(String fieldId) implements FillSpotChange {
        public Remove {
            Objects.requireNonNull(fieldId, "fieldId");
        }
    }

    /**
     * A new single-value spot on a PDF form: a box on page {@code
     * pageNumber} ({@link PdfRect} convention), called {@code label}. The
     * text is drawn in {@code style}, or, when that is null, in the look of
     * the words beside the box (the ordinary look when there are none, as on
     * a scan). {@code overflow} left null is {@link
     * PdfOverflowPolicy#SHRINK_TO_FIT}: text too long for the box is made
     * smaller rather than stopping export. {@code placedByModel} is as for
     * {@link Add}.
     */
    record AddBox(
            int pageNumber, PdfRect box, String label, FieldType type, PdfTextStyle style, boolean multiline, PdfOverflowPolicy overflow,
            boolean placedByModel) implements FillSpotChange {
        public AddBox {
            Objects.requireNonNull(box, "box");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(type, "type");
            overflow = overflow == null ? PdfOverflowPolicy.SHRINK_TO_FIT : overflow;
        }
    }

    /**
     * Puts a PDF spot's box somewhere else on the same page, or makes it
     * another size. Only a box Brownie draws can move: one of the PDF's own
     * form fields is where the form put it.
     */
    record MoveBox(String fieldId, PdfRect box) implements FillSpotChange {
        public MoveBox {
            Objects.requireNonNull(fieldId, "fieldId");
            Objects.requireNonNull(box, "box");
        }
    }

    /**
     * Changes how a PDF spot's box shows its text: the size it starts at,
     * what happens when the text is too long, and whether it wraps onto more
     * lines. A null part stays as it is; at least one is given.
     */
    record RestyleBox(String fieldId, Double sizePt, PdfOverflowPolicy overflow, Boolean multiline) implements FillSpotChange {
        public RestyleBox {
            Objects.requireNonNull(fieldId, "fieldId");
        }
    }
}
