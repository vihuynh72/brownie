package io.github.vihuynh72.brownie.worker.generation;

import io.github.vihuynh72.brownie.core.generation.CancellationSignal;
import io.github.vihuynh72.brownie.core.job.JobLeaseRepository;
import io.github.vihuynh72.brownie.core.job.JobLeaseToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps one claimed job's lease alive for as long as its attempt runs,
 * and is the attempt's {@link CancellationSignal}: the same heartbeat
 * that extends the lease is refused by the database the moment the job
 * is cancelled, its deadline passes, or another worker holds the lease,
 * so the next paid model call is never placed for an attempt that is no
 * longer authoritative. A heartbeat that could not reach the database at
 * all is retried on the next tick rather than treated as a refusal.
 *
 * <p>The first heartbeat runs on the calling thread before this returns,
 * so a job cancelled before its attempt even started is caught before
 * any model call, not after the first tick.
 */
final class LeaseHeartbeat implements CancellationSignal, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LeaseHeartbeat.class);

    private final JobLeaseRepository jobLeaseRepository;
    private final JobLeaseToken leaseToken;
    private final Duration leaseDuration;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean refused = new AtomicBoolean(false);

    private LeaseHeartbeat(JobLeaseRepository jobLeaseRepository, JobLeaseToken leaseToken, Duration leaseDuration) {
        this.jobLeaseRepository = Objects.requireNonNull(jobLeaseRepository, "jobLeaseRepository must not be null");
        this.leaseToken = Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lease-heartbeat-job-" + leaseToken.jobId());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Heartbeats once now, then every third of the lease duration until
     * {@link #close()}. A first heartbeat that is already refused (the job
     * was cancelled before this attempt started) schedules nothing: the
     * refusal is final for this attempt.
     */
    static LeaseHeartbeat start(JobLeaseRepository jobLeaseRepository, JobLeaseToken leaseToken, Duration leaseDuration) {
        LeaseHeartbeat heartbeat = new LeaseHeartbeat(jobLeaseRepository, leaseToken, leaseDuration);
        heartbeat.beat();
        if (!heartbeat.refused.get()) {
            long intervalMillis = Math.max(1_000, leaseDuration.toMillis() / 3);
            heartbeat.scheduler.scheduleAtFixedRate(heartbeat::beat, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }
        return heartbeat;
    }

    /** A refusal is sticky: later ticks do nothing, and only {@link #close()} stops the scheduler. */
    private void beat() {
        if (refused.get()) {
            return;
        }
        try {
            if (!jobLeaseRepository.heartbeat(leaseToken, leaseDuration)) {
                log.info("Lease heartbeat refused for job {} attempt {}; stopping before the next model call.",
                        leaseToken.jobId(), leaseToken.fencingToken());
                refused.set(true);
            }
        } catch (RuntimeException e) {
            log.warn("Lease heartbeat for job {} could not reach the database; will retry.", leaseToken.jobId(), e);
        }
    }

    /** True once the database has refused to extend this attempt's lease: cancelled, past its deadline, or no longer this worker's. */
    @Override
    public boolean isCancellationRequested() {
        return refused.get();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
