package io.github.vihuynh72.brownie.core.artifact;

import java.util.Objects;

/**
 * The uploaded content's own bytes do not match any allowed media type or
 * package signature, or match one in a form Brownie will not open. The
 * {@link Reason} is what a person is told; the message is for whoever reads
 * the logs and never quotes the file.
 */
public class UnsupportedArtifactTypeException extends RuntimeException {

    /** Why the file was refused, in the terms a person can act on. */
    public enum Reason {
        /** Not recognizably any document Brownie reads. */
        NOT_A_DOCUMENT,
        SPREADSHEET,
        PRESENTATION,
        /** Locked with a password, so its content cannot be read. */
        PASSWORD_PROTECTED,
        /** Protected by an organization's rights management. */
        RIGHTS_PROTECTED,
        /** Asks whoever opens it to fetch something from a network (a linked template, picture or object). */
        REMOTE_CONTENT,
        /** Recognizable, but malformed or built in a way no real document is. */
        DAMAGED
    }

    private final Reason reason;

    /** A refusal with no more specific reason than that the bytes are not a document Brownie reads. */
    public UnsupportedArtifactTypeException(String message) {
        this(Reason.NOT_A_DOCUMENT, message);
    }

    public UnsupportedArtifactTypeException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public Reason reason() {
        return reason;
    }
}
