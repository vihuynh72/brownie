package io.github.vihuynh72.brownie.core.compile;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Lets only so many renders run at once. A render is the most expensive
 * thing a request can ask for: a container with half a gigabyte and a
 * whole processor for up to a minute, started on the thread of whoever
 * asked. Without a ceiling, a handful of requests arriving together (or
 * one person pressing a button repeatedly) can take every processor the
 * host has. With one, the rest wait their turn for a bounded time and are
 * then told to try again, which costs nothing.
 *
 * <p>Turns are taken in arrival order, so a burst from one person cannot
 * keep starving a request that was already waiting.
 */
public final class ConcurrencyLimitedDocumentRenderer implements DocumentRenderer {

    private final DocumentRenderer delegate;
    private final Semaphore slots;
    private final Duration maxWait;

    public ConcurrencyLimitedDocumentRenderer(DocumentRenderer delegate, int maxConcurrent, Duration maxWait) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.maxWait = Objects.requireNonNull(maxWait, "maxWait");
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("At least one render must be allowed at a time.");
        }
        if (maxWait.isNegative()) {
            throw new IllegalArgumentException("maxWait must not be negative.");
        }
        this.slots = new Semaphore(maxConcurrent, true);
    }

    @Override
    public RenderedPdf renderToPdf(byte[] docxBytes) {
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
            return delegate.renderToPdf(docxBytes);
        } finally {
            slots.release();
        }
    }
}
