package io.github.vihuynh72.brownie.core.job;

import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;

import java.util.Objects;

/** A temporary object whose bytes and supported media type were read back before attachment. */
public record StagedJobOutput(StagedOutput stagedOutput, SupportedMediaType detectedMediaType) {

    public StagedJobOutput {
        Objects.requireNonNull(stagedOutput, "stagedOutput must not be null");
        Objects.requireNonNull(detectedMediaType, "detectedMediaType must not be null");
    }
}
