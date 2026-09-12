package io.github.vihuynh72.brownie.core.job;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A stable, machine-readable work category. It is deliberately an opaque
 * identifier rather than a user-supplied description, so queue metadata does
 * not become a second place for application content.
 */
public record JobType(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");

    public JobType {
        Objects.requireNonNull(value, "value must not be null");
        if (value.length() > 100 || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Job type must be a lowercase machine identifier up to 100 characters.");
        }
    }
}
