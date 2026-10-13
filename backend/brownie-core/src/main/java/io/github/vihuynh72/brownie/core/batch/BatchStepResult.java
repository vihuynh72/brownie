package io.github.vihuynh72.brownie.core.batch;

import java.util.List;

/**
 * What one bounded step did: rows it moved, how many are still to go, and
 * when to come back when capacity -- never the step -- ran out
 * ({@code retryAfterMs} is set only then; a step that merely hit its row or
 * time budget leaves it null and the caller loops straight on).
 */
public record BatchStepResult(int processed, int remaining, Long retryAfterMs, List<BatchRow> rows) {

    public BatchStepResult {
        rows = List.copyOf(rows);
    }
}
