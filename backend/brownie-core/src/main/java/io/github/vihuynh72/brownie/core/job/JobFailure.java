package io.github.vihuynh72.brownie.core.job;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A redacted worker outcome used to choose a retry, durable user wait, or
 * terminal failure. It intentionally carries no provider body or source text.
 */
public record JobFailure(
        JobFailureKind kind,
        String safeCode,
        String safeMessage,
        Duration providerRetryAfter) {

    private static final Pattern CODE_FORMAT = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");

    public JobFailure {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(safeCode, "safeCode must not be null");
        if (!CODE_FORMAT.matcher(safeCode).matches()) {
            throw new IllegalArgumentException("safeCode must be an uppercase machine identifier up to 100 characters.");
        }
        if (safeMessage != null && (safeMessage.isBlank() || safeMessage.length() > 500)) {
            throw new IllegalArgumentException("safeMessage must be non-blank when present and at most 500 characters.");
        }
        if (providerRetryAfter != null && (providerRetryAfter.isZero() || providerRetryAfter.isNegative())) {
            throw new IllegalArgumentException("providerRetryAfter must be positive when present.");
        }
        if (!kind.retriable() && providerRetryAfter != null) {
            throw new IllegalArgumentException("Only a transient failure may specify providerRetryAfter.");
        }
    }
}
