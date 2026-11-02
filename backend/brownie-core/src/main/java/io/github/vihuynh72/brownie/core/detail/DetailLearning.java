package io.github.vihuynh72.brownie.core.detail;

import java.time.Instant;
import java.util.List;

/**
 * What one "learn after approval" run wrote: for every key it set, the
 * value that was there before ({@code null} when the key was new), so
 * {@code undo} restores exactly this run's change and nothing else's.
 */
public record DetailLearning(long id, long ownerUserId, long documentId, List<WrittenKey> writes, Instant createdAt) {

    /**
     * One key the run set, the value it replaced ({@code null} when there
     * was none), and the value it wrote -- the undo check needs all three
     * to leave alone a value the person changed since.
     */
    public record WrittenKey(String key, String previousValue, String writtenValue) {
    }
}
