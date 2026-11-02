package io.github.vihuynh72.brownie.api.job;

import io.github.vihuynh72.brownie.core.job.Job;
import io.github.vihuynh72.brownie.core.job.JobEvent;
import io.github.vihuynh72.brownie.core.job.JobState;

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
        OffsetDateTime updatedAt,
        String failureMessage) {

    public static JobResponse from(Job job) {
        return from(job, null);
    }

    /**
     * The job's person-facing failure, read from the latest safe job event -- the same source the MCP
     * fill path reports through, never an exception's internals. Only a FAILED or DEAD job has one to
     * report; for any other state the fields are null.
     */
    public static JobResponse from(Job job, JobEvent latestEvent) {
        boolean failed = job.state() == JobState.FAILED || job.state() == JobState.DEAD;
        String failureMessage = failed && latestEvent != null ? latestEvent.safeMessage() : null;
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
                job.updatedAt(),
                failureMessage);
    }
}
