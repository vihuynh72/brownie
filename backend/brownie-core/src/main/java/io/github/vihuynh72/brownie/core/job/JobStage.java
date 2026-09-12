package io.github.vihuynh72.brownie.core.job;

import java.util.Objects;
import java.util.regex.Pattern;

/** A stable, machine-readable processing stage within a {@link JobType}. */
public record JobStage(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");

    public JobStage {
        Objects.requireNonNull(value, "value must not be null");
        if (value.length() > 100 || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Job stage must be a lowercase machine identifier up to 100 characters.");
        }
    }
}
