package io.github.vihuynh72.brownie.core.document;

/**
 * What filling a PDF, or checking the filled result, found about one value.
 * A blocking code means the value was not written (when the filler reports
 * it) or is not what the output shows (when the checker reports it), so the
 * output must not be exported; an informational code only says what was
 * done. The code is the identity a caller acts on; {@link
 * PdfFillFinding#detail()} holds the one fact each code names, as its
 * javadoc says.
 */
public enum PdfFillFindingCode {

    /** A character the font has no letter for. Detail: the character itself. */
    UNSUPPORTED_CHARACTER(true),

    /** The text does not fit, at its own size or, when it may shrink, at six points. */
    FIXED_FIELD_OVERFLOW(true),

    /** The text was made smaller to fit. Detail: the size used, in points ("8.5"). */
    FIELD_TEXT_SHRUNK(false),

    /** The text is longer than the field allows; it is never cut short. Detail: the field's limit ("10"). */
    MAX_LENGTH_EXCEEDED(true),

    /** The text is in a script whose letters join or run right to left (Arabic, Hebrew, the Indic scripts, Thai and others), which Brownie cannot write into a PDF yet. Detail: the first such character. */
    SCRIPT_NOT_SUPPORTED(true),

    /** The target cannot take text: no such field, a field that is not a text field or is read-only, or a box off its page. */
    FIELD_NOT_FILLABLE(true),

    /** Checking: the field's stored value is not the intended text. */
    FIELD_VALUE_NOT_STORED(true),

    /** Checking: a widget of the field has no drawn appearance, so readers that do not redraw fields would show nothing. */
    FIELD_APPEARANCE_MISSING(true),

    /** Checking: the text drawn inside the field's widgets or the box is not the intended text. Detail: what is drawn there. */
    FIELD_NOT_VISIBLE_IN_OUTPUT(true),

    /** Checking: text this fill added lies outside every place it was meant to go. Detail: the stray text. */
    TEXT_OUTSIDE_FILL_SPOT(true),

    /** Checking: a field this fill did not name changed. Detail: the field's full name. */
    OTHER_FIELD_CHANGED(true),

    /** Checking: the output does not have the same number of fields as the form it was filled from. */
    FIELD_COUNT_CHANGED(true);

    private final boolean blocking;

    PdfFillFindingCode(boolean blocking) {
        this.blocking = blocking;
    }

    public boolean blocking() {
        return blocking;
    }
}
