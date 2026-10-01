package io.github.vihuynh72.brownie.core.document;

/**
 * A PDF a person offered as a form to fill cannot be used as one, for a
 * reason that belongs to the whole file ({@link UnsupportedPdfFormReason}).
 * The message says what happened and what to do, in words for the person;
 * {@code detail} is what the reader found, for the record.
 */
public class UnusablePdfFormException extends RuntimeException {

    private final UnsupportedPdfFormReason reason;
    private final String detail;

    public UnusablePdfFormException(UnsupportedPdfFormReason reason, String detail) {
        super(messageFor(reason));
        this.reason = reason;
        this.detail = detail;
    }

    public UnsupportedPdfFormReason reason() {
        return reason;
    }

    public String detail() {
        return detail;
    }

    /** What a person is told for each reason, whichever step found it. */
    public static String messageFor(UnsupportedPdfFormReason reason) {
        return switch (reason) {
            case ENCRYPTED -> "This PDF is locked with a password or protection settings, so Brownie cannot fill it without"
                    + " removing that protection. Save an unlocked copy and upload that.";
            case SIGNED -> "This PDF has been signed. Filling it in would break the signature, so Brownie leaves it as it is.";
            case XFA -> "This PDF is a kind of form Brownie cannot fill yet.";
            case LAUNCH_ACTION -> "This PDF has actions that start other programs, so Brownie did not accept it.";
            case EMBEDDED_FILES -> "This PDF has other files attached inside it, so Brownie did not accept it.";
            case DOCUMENT_JAVASCRIPT -> "This PDF runs scripts when it is opened, so Brownie did not accept it.";
            case DAMAGED -> "Brownie could not read this PDF. It may be damaged. Save it again and upload the new copy.";
            case TOO_LARGE -> "This PDF has more pages, fields or content than Brownie fills in one form. Try a shorter PDF.";
        };
    }
}
