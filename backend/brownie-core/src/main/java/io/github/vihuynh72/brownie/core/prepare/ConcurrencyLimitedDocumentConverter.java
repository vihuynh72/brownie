package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.compile.RenderSlots;

import java.util.Objects;

/**
 * Takes a conversion's turn from the same {@link RenderSlots} rendering
 * uses. A conversion starts the same container a render does, so it has to
 * count against the same ceiling: with slots of its own, a burst of uploads
 * could fill the host while every render still found a free turn. When no
 * turn frees up in time the caller is told to try again
 * ({@code RenderCapacityExceededException}), exactly as a render would be.
 */
public final class ConcurrencyLimitedDocumentConverter implements DocumentConverter {

    private final DocumentConverter delegate;
    private final RenderSlots slots;

    public ConcurrencyLimitedDocumentConverter(DocumentConverter delegate, RenderSlots slots) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.slots = Objects.requireNonNull(slots, "slots");
    }

    @Override
    public ConvertedDocument convertToDocx(byte[] source, ConvertibleFormat format) {
        return slots.runInTurn(() -> delegate.convertToDocx(source, format));
    }
}
