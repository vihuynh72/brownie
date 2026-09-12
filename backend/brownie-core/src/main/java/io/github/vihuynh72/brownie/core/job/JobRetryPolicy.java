package io.github.vihuynh72.brownie.core.job;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Bounded exponential retry scheduling with deterministic per-attempt jitter.
 * The stable jitter avoids synchronized retry bursts while keeping a failed
 * attempt's scheduled time reproducible for diagnostics and tests.
 */
public final class JobRetryPolicy {

    private static final double MIN_JITTER_FACTOR = 0.80d;
    private static final double JITTER_SPAN = 0.40d;

    private final Duration baseDelay;
    private final Duration maximumDelay;

    public JobRetryPolicy(Duration baseDelay, Duration maximumDelay) {
        this.baseDelay = requirePositive(baseDelay, "baseDelay");
        this.maximumDelay = requirePositive(maximumDelay, "maximumDelay");
        if (maximumDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maximumDelay must be at least baseDelay.");
        }
    }

    public static JobRetryPolicy defaults() {
        return new JobRetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5));
    }

    public OffsetDateTime nextRetryAt(
            int completedAttemptCount,
            long jobId,
            long fencingToken,
            OffsetDateTime now,
            Duration providerRetryAfter) {
        if (completedAttemptCount < 1) {
            throw new IllegalArgumentException("completedAttemptCount must be positive.");
        }
        if (jobId <= 0 || fencingToken <= 0) {
            throw new IllegalArgumentException("jobId and fencingToken must be positive.");
        }
        Objects.requireNonNull(now, "now must not be null");
        if (providerRetryAfter != null && (providerRetryAfter.isZero() || providerRetryAfter.isNegative())) {
            throw new IllegalArgumentException("providerRetryAfter must be positive when present.");
        }

        Duration exponential = cappedExponentialDelay(completedAttemptCount);
        long jitterSeed = mix(jobId) ^ Long.rotateLeft(mix(fencingToken), 17) ^ completedAttemptCount;
        double normalized = ((jitterSeed >>> 11) * 0x1.0p-53d);
        Duration jittered = scale(exponential, MIN_JITTER_FACTOR + (normalized * JITTER_SPAN));
        Duration selected = providerRetryAfter == null || jittered.compareTo(providerRetryAfter) >= 0
                ? jittered
                : providerRetryAfter;
        return now.plus(selected);
    }

    private Duration cappedExponentialDelay(int completedAttemptCount) {
        Duration delay = baseDelay;
        for (int index = 1; index < completedAttemptCount && delay.compareTo(maximumDelay) < 0; index++) {
            delay = delay.multipliedBy(2);
            if (delay.compareTo(maximumDelay) > 0) {
                delay = maximumDelay;
            }
        }
        return delay;
    }

    private static Duration scale(Duration duration, double factor) {
        long nanos = Math.round(duration.toNanos() * factor);
        return Duration.ofNanos(Math.max(1L, nanos));
    }

    private static long mix(long value) {
        long mixed = value + 0x9E3779B97F4A7C15L;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }
}
