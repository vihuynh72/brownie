package io.github.vihuynh72.brownie.core.job;

import java.util.Objects;

/** The result and, when available, immutable artifact reference for one output publication attempt. */
public record JobOutputPublication(JobOutputPublicationResult result, Long artifactId) {

    public JobOutputPublication {
        Objects.requireNonNull(result, "result must not be null");
        boolean requiresArtifact = result == JobOutputPublicationResult.PUBLISHED
                || result == JobOutputPublicationResult.ALREADY_PUBLISHED;
        if (requiresArtifact != (artifactId != null)) {
            throw new IllegalArgumentException("Only a published output result may carry an artifact id.");
        }
        if (artifactId != null && artifactId <= 0) {
            throw new IllegalArgumentException("artifactId must be positive when present.");
        }
    }

    public boolean hasPublishedArtifact() {
        return artifactId != null;
    }
}
