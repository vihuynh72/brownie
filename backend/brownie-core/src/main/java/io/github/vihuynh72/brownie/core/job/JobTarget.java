package io.github.vihuynh72.brownie.core.job;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A tenant-owned resource identity and immutable version used for job
 * deduplication. It carries identifiers only, never a resource body.
 */
public record JobTarget(String resourceType, long resourceId, long resourceVersion) {

    private static final Pattern TYPE_FORMAT = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");

    public JobTarget {
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        if (resourceType.length() > 100 || !TYPE_FORMAT.matcher(resourceType).matches()) {
            throw new IllegalArgumentException("Resource type must be a lowercase machine identifier up to 100 characters.");
        }
        if (resourceId <= 0) {
            throw new IllegalArgumentException("resourceId must be positive.");
        }
        if (resourceVersion <= 0) {
            throw new IllegalArgumentException("resourceVersion must be positive.");
        }
    }
}
