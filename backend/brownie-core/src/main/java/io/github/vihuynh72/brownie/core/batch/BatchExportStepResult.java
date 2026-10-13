package io.github.vihuynh72.brownie.core.batch;

import java.util.List;

/**
 * The shape of one bounded export-advance call: the items it resolved,
 * how many items are still PENDING, and a render-capacity hint when the
 * step gave up early for that reason.
 */
public record BatchExportStepResult(
        int processed,
        int remaining,
        Long retryAfterMs,
        BatchExportState state,
        List<BatchExportItem> items) {
}
