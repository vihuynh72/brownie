package io.github.vihuynh72.brownie.api.job;

import io.github.vihuynh72.brownie.core.job.Job;

import java.time.OffsetDateTime;

/** The wire shape of one durable job -- its own route's response, and embedded wherever another resource reports the job that carries it. */
public record JobResponse(
        long id,
        String type,
        String resourceType,
        long resourceId,
        long resourceVersion,
        String stage,
        String state,
        int attemptCount,
        OffsetDateTime availableAt,
        OffsetDateTime deadlineAt,
        OffsetDateTime cancellationRequestedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static JobResponse from(Job job) {
        return new JobResponse(
                job.id(),
                job.type().value(),
                job.target().resourceType(),
                job.target().resourceId(),
                job.target().resourceVersion(),
                job.stage().value(),
                job.state().name(),
                job.attemptCount(),
                job.availableAt(),
                job.deadlineAt(),
                job.cancellationRequestedAt(),
                job.createdAt(),
                job.updatedAt());
    }
}
