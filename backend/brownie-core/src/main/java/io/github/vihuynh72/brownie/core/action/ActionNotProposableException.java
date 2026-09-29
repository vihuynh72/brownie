package io.github.vihuynh72.brownie.core.action;

import java.util.Objects;

/** A change that cannot be proposed as asked; {@code reason} says why, and nothing was recorded or sent. */
public class ActionNotProposableException extends RuntimeException {

    public enum Reason {
        /** The document has not been exported: only an export someone approved is saved. */
        NO_EXPORT,
        /** The document changed after its latest export; export it again first. */
        EXPORT_STALE,
        /** The format asked for is not one the latest export approved. */
        FORMAT_NOT_EXPORTED,
        /** The file is larger than Brownie saves in one request. */
        FILE_TOO_LARGE,
        /** Some text to be sent holds characters that reorder it or cannot be seen. */
        HIDDEN_CHARACTERS,
        /** A local time that does not happen on that day, because the clocks go forward over it. */
        TIME_SKIPPED,
        /** A value in the request is outside what this kind of change allows. */
        INVALID
    }

    private final Reason reason;

    public ActionNotProposableException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
