package io.github.vihuynh72.brownie.core.generation;

import java.util.Objects;

/**
 * One source excerpt, addressed by the real, already-persisted {@link
 * io.github.vihuynh72.brownie.core.evidence.SourceSpan} ID that names it
 * -- the exact citation label a model reply must use, since there is no
 * separate local numbering scheme to translate back and forth between.
 */
public record LabeledExcerpt(long spanId, String text) {

    public LabeledExcerpt {
        Objects.requireNonNull(text, "text");
        if (spanId <= 0) {
            throw new IllegalArgumentException("spanId must be positive, was " + spanId);
        }
    }
}
