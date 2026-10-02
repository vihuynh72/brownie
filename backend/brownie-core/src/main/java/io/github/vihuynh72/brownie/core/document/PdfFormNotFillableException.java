package io.github.vihuynh72.brownie.core.document;

/**
 * The PDF handed to the filler is one it must not fill at all: locked,
 * signed, an XFA form, or unreadable. Reading the form refuses all of these
 * first, so this is the filler's own guard, not a case a person should
 * reach.
 */
public class PdfFormNotFillableException extends RuntimeException {

    private final UnsupportedPdfFormReason reason;

    public PdfFormNotFillableException(UnsupportedPdfFormReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public PdfFormNotFillableException(UnsupportedPdfFormReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public UnsupportedPdfFormReason reason() {
        return reason;
    }
}
