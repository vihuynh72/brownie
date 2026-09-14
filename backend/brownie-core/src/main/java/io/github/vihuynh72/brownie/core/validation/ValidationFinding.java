package io.github.vihuynh72.brownie.core.validation;

import java.util.Objects;

/**
 * One independent check's own result. {@code fieldId} is null for a
 * finding that is not about one specific field (for example a
 * protected-region check keyed by its own binding target rather than a
 * template field). {@code message} is a human-readable detail only --
 * never parsed back by a caller, which must key off {@code code} instead
 * (see {@link ValidationFindingCode}).
 */
public record ValidationFinding(ValidationFindingCode code, String fieldId, String message) {

    public ValidationFinding {
        Objects.requireNonNull(code, "code");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank.");
        }
    }

    public ValidationSeverity severity() {
        return code.severity();
    }
}
