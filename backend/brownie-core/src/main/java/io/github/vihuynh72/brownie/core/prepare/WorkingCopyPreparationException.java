package io.github.vihuynh72.brownie.core.prepare;

/**
 * No clean working copy could be made. {@link Reason#DAMAGED}: the bytes
 * could not be read as a Word document at all. {@link Reason#NOT_CLEAN}:
 * the copy made still holds something the steps should have taken out (a
 * macro, a signature, a control, or anything else the reader refuses), and
 * is never handed on.
 */
public class WorkingCopyPreparationException extends RuntimeException {

    public enum Reason {
        DAMAGED,
        NOT_CLEAN
    }

    private final Reason reason;

    public WorkingCopyPreparationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public WorkingCopyPreparationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
