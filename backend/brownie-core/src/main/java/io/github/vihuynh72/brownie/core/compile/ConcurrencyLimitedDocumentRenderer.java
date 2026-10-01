package io.github.vihuynh72.brownie.core.compile;

import java.time.Duration;
import java.util.Objects;

/**
 * Lets only so many renders run at once. A render is the most expensive
 * thing a request can ask for: a container with half a gigabyte and a
 * whole processor for up to a minute, started on the thread of whoever
 * asked. Without a ceiling, a handful of requests arriving together (or
 * one person pressing a button repeatedly) can take every processor the
 * host has. With one, the rest wait their turn for a bounded time and are
 * then told to try again, which costs nothing.
 *
 * <p>The turns themselves are {@link RenderSlots}, which converting another
 * format to Word shares, since it starts the same container.
 */
public final class ConcurrencyLimitedDocumentRenderer implements DocumentRenderer {

    private final DocumentRenderer delegate;
    private final RenderSlots slots;

    public ConcurrencyLimitedDocumentRenderer(DocumentRenderer delegate, int maxConcurrent, Duration maxWait) {
        this(delegate, new RenderSlots(maxConcurrent, maxWait));
    }

    public ConcurrencyLimitedDocumentRenderer(DocumentRenderer delegate, RenderSlots slots) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.slots = Objects.requireNonNull(slots, "slots");
    }

    @Override
    public RenderedPdf renderToPdf(byte[] docxBytes) {
        return slots.runInTurn(() -> delegate.renderToPdf(docxBytes));
    }
}
