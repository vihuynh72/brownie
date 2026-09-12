package io.github.vihuynh72.brownie.core.job;

import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.Objects;

/** The metadata-only command that creates or reuses a durable job. */
public record EnqueueJobCommand(
        IdempotencyKey idempotencyKey,
        CanonicalRequestHash requestHash,
        JobType jobType,
        JobTarget target,
        JobStage stage,
        CanonicalRequestHash processingConfigurationHash,
        OffsetDateTime availableAt,
        OffsetDateTime deadlineAt) {

    private static final Duration DEFAULT_DEADLINE_WINDOW = Duration.ofHours(1);

    public EnqueueJobCommand(
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            JobType jobType,
            JobTarget target,
            JobStage stage,
            CanonicalRequestHash processingConfigurationHash,
            OffsetDateTime availableAt) {
        this(
                idempotencyKey,
                requestHash,
                jobType,
                target,
                stage,
                processingConfigurationHash,
                availableAt,
                availableAt == null ? null : availableAt.plus(DEFAULT_DEADLINE_WINDOW));
    }

    public EnqueueJobCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(requestHash, "requestHash must not be null");
        Objects.requireNonNull(jobType, "jobType must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(processingConfigurationHash, "processingConfigurationHash must not be null");
        Objects.requireNonNull(availableAt, "availableAt must not be null");
        Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
        if (!deadlineAt.isAfter(availableAt)) {
            throw new IllegalArgumentException("deadlineAt must be after availableAt.");
        }
    }
}
