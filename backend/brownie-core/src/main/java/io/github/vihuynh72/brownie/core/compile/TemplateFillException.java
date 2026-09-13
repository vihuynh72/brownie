package io.github.vihuynh72.brownie.core.compile;

import java.util.Objects;

/** A template's own bindings could not be applied to its declared source bytes. */
public class TemplateFillException extends RuntimeException {

    private final TemplateFillProblemReason reason;

    public TemplateFillException(TemplateFillProblemReason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public TemplateFillException(TemplateFillProblemReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public TemplateFillProblemReason reason() {
        return reason;
    }
}
