package io.github.vihuynh72.brownie.core.compile;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The turns at the isolated document container, shared by everything that
 * starts one: rendering a Word document to PDF, and converting another
 * format to Word. Both cost the same (a container with half a gigabyte and
 * a whole processor for up to a minute, started on the thread of whoever
 * asked), so both have to count against the one ceiling, or a burst of one
 * could take every processor the host has while the other kept to its
 * limit. Past the ceiling, the rest wait their turn for a bounded time and
 * are then told to try again, which costs nothing.
 *
 * <p>Turns are taken in arrival order, so a burst from one person cannot
 * keep starving a request that was already waiting.
 */
public final class RenderSlots {

    private final Semaphore slots;
    private final Duration maxWait;

    public RenderSlots(int maxConcurrent, Duration maxWait) {
        this.maxWait = Objects.requireNonNull(maxWait, "maxWait");
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("At least one render must be allowed at a time.");
        }
        if (maxWait.isNegative()) {
            throw new IllegalArgumentException("maxWait must not be negative.");
        }
        this.slots = new Semaphore(maxConcurrent, true);
    }

    /** Runs {@code work} once a turn is free, and gives the turn back however the work ends. */
    public <T> T runInTurn(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RenderCapacityExceededException("Rendering was interrupted while waiting for its turn.");
        }
        if (!acquired) {
            throw new RenderCapacityExceededException("Every render slot is busy. Try again shortly.");
        }
        try {
            return work.get();
        } finally {
            slots.release();
        }
    }
}
