package io.github.vihuynh72.brownie.core.job;

import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;

import java.time.Duration;
import java.util.Optional;

/**
 * Narrow worker-only persistence boundary. Each successful state mutation
 * must append a matching {@link JobEvent} in the same transaction. Methods
 * that publish or attach a result require a current lease owner and fencing
 * token, and must reject a cancellation request before publishing anything.
 */
public interface JobLeaseRepository {

    /**
     * Claims one eligible job using the database clock. The requested duration
     * is bounded by the worker configuration, so a caller cannot steal a live
     * lease by supplying its own future clock value.
     */
    Optional<LeasedJob> claimNext(WorkerId worker, Duration leaseDuration);

    /** Returns false when the lease was lost or the job was cancelled. */
    boolean heartbeat(JobLeaseToken leaseToken, Duration leaseDuration);

    /**
     * Appends safe, persisted progress only while the same unexpired lease is
     * current. The event stream is delivery-only; this row is authoritative.
     */
    boolean reportProgress(JobLeaseToken leaseToken, JobProgress progress);

    /**
     * Releases a lease after retry scheduling, retry exhaustion, or a durable
     * wait for user input. If cancellation was requested, implementations
     * finalize CANCELLED instead of scheduling more work.
     */
    JobReleaseResult release(JobLeaseToken leaseToken, JobRelease release);

    /**
     * Classifies a failed attempt and applies the configured bounded retry
     * policy, a durable input wait, or a terminal failure without exposing a
     * provider error body to the queue.
     */
    JobReleaseResult releaseAfterFailure(JobLeaseToken leaseToken, JobFailure failure);

    /**
     * Attempts a final publication only when the lease, cancellation state,
     * and expected target all still match the job row atomically.
     */
    JobCompletionResult complete(JobLeaseToken leaseToken, JobCompletion completion);

    /**
     * Records temporary output metadata only while this exact lease remains
     * current and cancellation has not been requested.
     */
    Optional<StagedOutput> recordStagedOutput(JobLeaseToken leaseToken, StagedOutputRequest output);

    /**
     * Attaches an already re-verified temporary object and terminalizes its
     * job in one database transaction. The media type is observed from the
     * object's bytes, never accepted from a browser request.
     */
    JobOutputPublication publishStagedOutput(
            JobLeaseToken leaseToken, String outputKind, SupportedMediaType detectedMediaType);
}
