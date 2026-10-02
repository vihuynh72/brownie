package io.github.vihuynh72.brownie.core.prepare;

import java.util.Objects;

/**
 * The file was given to the converter and did not come back as a usable
 * Word document. The reason says which of three things happened, because
 * each one asks something different of the person: a file that does not
 * open is worth saving again from the program that made it, a damaged one
 * is worth repairing first, and one that took too long may simply be too
 * large or too complicated.
 */
public class DocumentConversionException extends RuntimeException {

    public enum Reason {
        /** The converter could not read the file as the format it was said to be, and produced nothing. */
        CANNOT_OPEN,
        /** Reading the file broke the converter, hit one of its limits, or produced something that is not a Word document. */
        DAMAGED,
        /** The conversion was still running when its time ran out, and was stopped. */
        TIMED_OUT
    }

    private final Reason reason;

    public DocumentConversionException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public DocumentConversionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
