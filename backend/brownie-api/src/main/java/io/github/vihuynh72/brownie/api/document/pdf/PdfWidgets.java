package io.github.vihuynh72.brownie.api.document.pdf;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.form.PDButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDPushButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;

/**
 * Which of a field's widgets a person's reader draws, decided once for the
 * reader, the filler and the checker. A widget that is hidden (a form may
 * hide a field until a box is ticked), kept off the screen, or has no room
 * at all shows nothing: no value can be written into it or read back from
 * it, so it is not a place to fill and nothing is checked there.
 */
final class PdfWidgets {

    /** Under a point across or down there is no room to write anything. */
    static final double SMALLEST_SIDE = 1;

    private PdfWidgets() {
    }

    static boolean drawn(PDAnnotationWidget widget) {
        PDRectangle rect = widget.getRectangle();
        return rect != null
                && !widget.isHidden() && !widget.isNoView() && !widget.isInvisible()
                && Math.abs(rect.getWidth()) >= SMALLEST_SIDE && Math.abs(rect.getHeight()) >= SMALLEST_SIDE;
    }

    /**
     * Whether a field holds a value that some place it is shown has no
     * drawing of: a form that asks readers to draw its fields
     * ({@code NeedAppearances}) may leave them to, and such a value shows
     * only while the form keeps asking.
     */
    static boolean valueLeftForTheReaderToDraw(PDTerminalField field) {
        if (field instanceof PDSignatureField || field instanceof PDPushButton) {
            return false;
        }
        // A choice with nothing chosen writes itself as "[]"; its list of choices is what says whether it holds anything.
        boolean empty = field instanceof PDChoice choice
                ? choice.getValue().isEmpty()
                : field.getValueAsString() == null || field.getValueAsString().isEmpty()
                        || (field instanceof PDButton && "Off".equals(field.getValueAsString()));
        if (empty) {
            return false;
        }
        for (PDAnnotationWidget widget : field.getWidgets()) {
            PDAppearanceDictionary appearance = widget.getAppearance();
            if (drawn(widget) && (appearance == null || appearance.getNormalAppearance() == null)) {
                return true;
            }
        }
        return false;
    }
}
