package io.github.vihuynh72.brownie.core.generation;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * How many times, and how patiently, a model request that failed in
 * transit is sent again. This is separate from the single repair a
 * structurally invalid reply is allowed: a throttled or dropped request
 * never produced a reply to repair. Every retry is a new physical request
 * and is reserved against the run's budget like any other, so retries can
 * never take a run past its bound on requests or spend.
 */
public record TransportRetryPolicy(int maxRetries, Duration baseDelay, Duration maxDelay, Pause pause) {

    /** How the wait between two tries is spent; replaceable so a test does not really sleep. */
    @FunctionalInterface
    public interface Pause {
        void forDuration(Duration duration) throws InterruptedException;
    }

    public TransportRetryPolicy {
        Objects.requireNonNull(baseDelay, "baseDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        Objects.requireNonNull(pause, "pause");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative.");
        }
        if (baseDelay.isNegative() || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("Delays must not be negative, and the maximum must not be below the base.");
        }
    }

    /** A transport failure is reported at once. What a caller on a person's own request thread wants. */
    public static TransportRetryPolicy none() {
        return new TransportRetryPolicy(0, Duration.ZERO, Duration.ZERO, duration -> { });
    }

    /** Two more tries, about one and two seconds apart: enough to ride out a throttle, short enough to stay inside a job's lease. */
    public static TransportRetryPolicy standard() {
        return new TransportRetryPolicy(2, Duration.ofSeconds(1), Duration.ofSeconds(8), duration -> Thread.sleep(duration.toMillis()));
    }

    /** Doubles per failure up to the maximum, then moves the result by up to a fifth either way so callers that failed together do not retry together. */
    Duration delayBefore(int retryNumber) {
        long doubled = baseDelay.toMillis() << Math.min(retryNumber - 1, 20);
        long capped = Math.min(doubled, maxDelay.toMillis());
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return Duration.ofMillis(Math.round(capped * jitter));
    }
}
