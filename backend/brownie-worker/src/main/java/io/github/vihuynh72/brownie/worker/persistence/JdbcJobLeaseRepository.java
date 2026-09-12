package io.github.vihuynh72.brownie.worker.persistence;

import io.github.vihuynh72.brownie.core.job.Job;
import io.github.vihuynh72.brownie.core.job.JobCompletion;
import io.github.vihuynh72.brownie.core.job.JobCompletionResult;
import io.github.vihuynh72.brownie.core.job.JobFailure;
import io.github.vihuynh72.brownie.core.job.JobLease;
import io.github.vihuynh72.brownie.core.job.JobLeaseRepository;
import io.github.vihuynh72.brownie.core.job.JobLeaseToken;
import io.github.vihuynh72.brownie.core.job.JobOutputPublication;
import io.github.vihuynh72.brownie.core.job.JobOutputPublicationResult;
import io.github.vihuynh72.brownie.core.job.JobProgress;
import io.github.vihuynh72.brownie.core.job.JobRelease;
import io.github.vihuynh72.brownie.core.job.JobReleaseResult;
import io.github.vihuynh72.brownie.core.job.JobRetryPolicy;
import io.github.vihuynh72.brownie.core.job.JobStage;
import io.github.vihuynh72.brownie.core.job.JobState;
import io.github.vihuynh72.brownie.core.job.JobTarget;
import io.github.vihuynh72.brownie.core.job.JobType;
import io.github.vihuynh72.brownie.core.job.LeasedJob;
import io.github.vihuynh72.brownie.core.job.StagedOutput;
import io.github.vihuynh72.brownie.core.job.StagedOutputRequest;
import io.github.vihuynh72.brownie.core.job.StagedOutputState;
import io.github.vihuynh72.brownie.core.job.WorkerId;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Worker-side persistence adapter. The worker credential executes typed
 * database routines only; those routines lock the job, check the database
 * clock and lease fence, then append durable event/outbox metadata atomically.
 */
@Repository
public class JdbcJobLeaseRepository implements JobLeaseRepository {

    private static final int RECOVERY_BATCH_SIZE = 32;

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final int maxAttempts;
    private final Duration maxLeaseDuration;
    private final JobRetryPolicy retryPolicy;

    public JdbcJobLeaseRepository(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            @Value("${brownie.worker.jobs.max-attempts:3}") int maxAttempts,
            @Value("${brownie.worker.jobs.max-lease:PT5M}") Duration maxLeaseDuration,
            @Value("${brownie.worker.jobs.retry-base:PT1S}") Duration retryBaseDelay,
            @Value("${brownie.worker.jobs.retry-max:PT5M}") Duration retryMaximumDelay) {
        this.jdbcTemplateProvider = Objects.requireNonNull(jdbcTemplateProvider, "jdbcTemplateProvider must not be null");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("brownie.worker.jobs.max-attempts must be at least one.");
        }
        if (maxLeaseDuration == null || maxLeaseDuration.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("brownie.worker.jobs.max-lease must be at least one millisecond.");
        }
        this.maxAttempts = maxAttempts;
        this.maxLeaseDuration = maxLeaseDuration;
        this.retryPolicy = new JobRetryPolicy(retryBaseDelay, retryMaximumDelay);
    }

    @Override
    @Transactional
    public Optional<LeasedJob> claimNext(WorkerId worker, Duration leaseDuration) {
        Objects.requireNonNull(worker, "worker must not be null");
        requireLeaseDuration(leaseDuration);

        List<Job> claimed = jdbc().query(
                "SELECT * FROM public.worker_claim_next(?, ?, ?, ?)",
                this::mapJob,
                worker.value(),
                leaseDuration.toMillis(),
                maxAttempts,
                RECOVERY_BATCH_SIZE);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        Job job = claimed.get(0);
        return Optional.of(new LeasedJob(job, new JobLeaseToken(job.id(), worker.value(), job.fencingToken())));
    }

    @Override
    @Transactional
    public boolean heartbeat(JobLeaseToken leaseToken, Duration leaseDuration) {
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        requireLeaseDuration(leaseDuration);
        return Boolean.TRUE.equals(jdbc().queryForObject(
                "SELECT public.worker_heartbeat(?, ?, ?, ?)",
                Boolean.class,
                leaseToken.jobId(),
                leaseToken.workerId(),
                leaseToken.fencingToken(),
                leaseDuration.toMillis()));
    }

    @Override
    @Transactional
    public boolean reportProgress(JobLeaseToken leaseToken, JobProgress progress) {
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(progress, "progress must not be null");
        return Boolean.TRUE.equals(jdbc().queryForObject(
                "SELECT public.worker_record_progress(?, ?, ?, ?, ?, ?)",
                Boolean.class,
                leaseToken.jobId(),
                leaseToken.workerId(),
                leaseToken.fencingToken(),
                progress.safeMessage(),
                progress.current(),
                progress.total()));
    }

    @Override
    @Transactional
    public JobReleaseResult release(JobLeaseToken leaseToken, JobRelease release) {
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(release, "release must not be null");
        return releaseThroughRoutine(leaseToken, release);
    }

    @Override
    @Transactional
    public JobReleaseResult releaseAfterFailure(JobLeaseToken leaseToken, JobFailure failure) {
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(failure, "failure must not be null");

        String message = failureMessage(failure);
        Optional<Job> liveLease = jdbc().query(
                        "SELECT * FROM public.worker_get_live_lease(?, ?, ?)",
                        this::mapJob,
                        leaseToken.jobId(),
                        leaseToken.workerId(),
                        leaseToken.fencingToken())
                .stream()
                .findFirst();

        JobRelease release = liveLease
                .map(job -> releaseForFailure(job, failure, message))
                // The release routine still distinguishes cancellation from a
                // lost lease after it locks the authoritative row.
                .orElseGet(() -> new JobRelease(JobState.DEAD, null, message));
        return releaseThroughRoutine(leaseToken, release);
    }

    @Override
    @Transactional
    public JobCompletionResult complete(JobLeaseToken leaseToken, JobCompletion completion) {
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(completion, "completion must not be null");

        String result = jdbc().queryForObject(
                "SELECT public.worker_complete(?, ?, ?, ?, ?, ?, ?, ?)",
                String.class,
                leaseToken.jobId(),
                leaseToken.workerId(),
                leaseToken.fencingToken(),
                completion.expectedTarget().resourceType(),
                completion.expectedTarget().resourceId(),
                completion.expectedTarget().resourceVersion(),
                completion.finalState().name(),
                completion.safeMessage());
        return JobCompletionResult.valueOf(requireRoutineResult(result, "completion"));
    }

    @Override
    @Transactional
    public Optional<StagedOutput> recordStagedOutput(JobLeaseToken leaseToken, StagedOutputRequest output) {
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(output, "output must not be null");

        List<StagedOutput> outputs = jdbc().query(
                "SELECT * FROM public.worker_record_staged_output(?, ?, ?, ?, ?, ?, ?, ?)",
                JdbcJobLeaseRepository::mapStagedOutput,
                leaseToken.jobId(),
                leaseToken.workerId(),
                leaseToken.fencingToken(),
                output.outputKind(),
                output.objectKey(),
                output.sha256(),
                output.byteCount(),
                output.expiresAt());
        if (outputs.isEmpty()) {
            return Optional.empty();
        }
        StagedOutput staged = outputs.get(0);
        if (!sameOutput(staged.metadata(), output)) {
            throw new IllegalStateException("This lease already recorded a different temporary output for " + output.outputKind() + ".");
        }
        return Optional.of(staged);
    }

    @Override
    @Transactional
    public JobOutputPublication publishStagedOutput(
            JobLeaseToken leaseToken, String outputKind, SupportedMediaType detectedMediaType) {
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(outputKind, "outputKind must not be null");
        Objects.requireNonNull(detectedMediaType, "detectedMediaType must not be null");
        List<JobOutputPublication> publications = jdbc().query(
                "SELECT * FROM public.worker_publish_staged_output(?, ?, ?, ?, ?)",
                (resultSet, rowNumber) -> new JobOutputPublication(
                        JobOutputPublicationResult.valueOf(resultSet.getString("outcome")),
                        resultSet.getObject("artifact_id", Long.class)),
                leaseToken.jobId(),
                leaseToken.workerId(),
                leaseToken.fencingToken(),
                outputKind,
                detectedMediaType.name());
        if (publications.size() != 1) {
            throw new IllegalStateException("The worker output-publication routine did not return exactly one outcome.");
        }
        return publications.get(0);
    }

    private JobReleaseResult releaseThroughRoutine(JobLeaseToken leaseToken, JobRelease release) {
        String result = jdbc().queryForObject(
                "SELECT public.worker_release(?, ?, ?, ?, ?, ?, ?)",
                String.class,
                leaseToken.jobId(),
                leaseToken.workerId(),
                leaseToken.fencingToken(),
                release.nextState().name(),
                release.availableAt(),
                release.safeMessage(),
                maxAttempts);
        return JobReleaseResult.valueOf(requireRoutineResult(result, "release"));
    }

    private JobRelease releaseForFailure(Job job, JobFailure failure, String message) {
        return switch (failure.kind()) {
            case WAITING_FOR_INPUT -> new JobRelease(JobState.WAITING_FOR_INPUT, null, message);
            case DETERMINISTIC -> new JobRelease(JobState.DEAD, null, message);
            case TRANSIENT_PROVIDER, TRANSIENT_SERVER, TRANSIENT_NETWORK -> retryRelease(job, failure, message);
        };
    }

    private Job mapJob(ResultSet resultSet, int rowNumber) throws SQLException {
        String leaseOwner = resultSet.getString("lease_owner");
        JobLease lease = leaseOwner == null
                ? null
                : new JobLease(leaseOwner, resultSet.getObject("lease_expires_at", OffsetDateTime.class));
        return new Job(
                resultSet.getLong("id"),
                resultSet.getLong("workspace_id"),
                resultSet.getLong("requested_by_user_id"),
                new JobType(resultSet.getString("job_type")),
                new JobTarget(
                        resultSet.getString("resource_type"),
                        resultSet.getLong("resource_id"),
                        resultSet.getLong("resource_version")),
                new JobStage(resultSet.getString("stage")),
                new io.github.vihuynh72.brownie.core.job.CanonicalRequestHash(
                        resultSet.getString("processing_configuration_hash")),
                JobState.valueOf(resultSet.getString("state")),
                resultSet.getInt("attempt_count"),
                resultSet.getObject("available_at", OffsetDateTime.class),
                resultSet.getObject("deadline_at", OffsetDateTime.class),
                resultSet.getObject("cancellation_requested_at", OffsetDateTime.class),
                resultSet.getLong("fencing_token"),
                lease,
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class));
    }

    static StagedOutput mapStagedOutput(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StagedOutput(
                resultSet.getLong("id"),
                resultSet.getLong("workspace_id"),
                resultSet.getLong("job_id"),
                resultSet.getString("worker_id"),
                resultSet.getLong("fencing_token"),
                new StagedOutputRequest(
                        resultSet.getString("output_kind"),
                        resultSet.getString("object_key"),
                        resultSet.getString("sha256"),
                        resultSet.getObject("byte_count", Long.class),
                        resultSet.getObject("expires_at", OffsetDateTime.class)),
                StagedOutputState.valueOf(resultSet.getString("state")),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("verified_at", OffsetDateTime.class),
                resultSet.getObject("attached_at", OffsetDateTime.class),
                resultSet.getObject("cleaned_at", OffsetDateTime.class));
    }

    private static boolean sameOutput(StagedOutputRequest left, StagedOutputRequest right) {
        return left.outputKind().equals(right.outputKind())
                && left.objectKey().equals(right.objectKey())
                && Objects.equals(left.sha256(), right.sha256())
                && Objects.equals(left.byteCount(), right.byteCount());
    }

    private JobRelease retryRelease(Job job, JobFailure failure, String message) {
        if (job.attemptCount() >= maxAttempts) {
            return new JobRelease(JobState.DEAD, null, "Retry limit reached: " + message);
        }
        OffsetDateTime retryAt = retryPolicy.nextRetryAt(
                job.attemptCount(),
                job.id(),
                job.fencingToken(),
                databaseNow(),
                failure.providerRetryAfter());
        if (!retryAt.isBefore(job.deadlineAt())) {
            return new JobRelease(JobState.DEAD, null, "Job deadline prevents another retry: " + message);
        }
        return new JobRelease(JobState.QUEUED, retryAt, message);
    }

    private OffsetDateTime databaseNow() {
        return jdbc().queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    }

    private static String failureMessage(JobFailure failure) {
        return failure.safeMessage() == null
                ? failure.safeCode()
                : failure.safeCode() + ": " + failure.safeMessage();
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate template = jdbcTemplateProvider.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("A database-backed worker operation requires a configured datasource.");
        }
        return template;
    }

    private static String requireRoutineResult(String result, String operation) {
        if (result == null) {
            throw new IllegalStateException("The worker " + operation + " routine did not return a result.");
        }
        return result;
    }

    private void requireLeaseDuration(Duration leaseDuration) {
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        if (leaseDuration.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("Lease duration must be at least one millisecond.");
        }
        if (leaseDuration.compareTo(maxLeaseDuration) > 0) {
            throw new IllegalArgumentException("Lease duration exceeds brownie.worker.jobs.max-lease.");
        }
    }
}
