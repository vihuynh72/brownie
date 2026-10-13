package io.github.vihuynh72.brownie.api.batch;

import io.github.vihuynh72.brownie.core.batch.BatchExportStepResult;

import java.util.List;

/**
 * What one advance call did: the items it resolved, how many are still
 * PENDING, the run's state, and a pause hint when render capacity -- not
 * the step -- ran out.
 */
record BatchExportStepResponse(
        int processed,
        int remaining,
        Long retryAfterMs,
        String state,
        List<BatchExportResponse.ItemResponse> items) {

    static BatchExportStepResponse from(BatchExportStepResult result) {
        return new BatchExportStepResponse(
                result.processed(),
                result.remaining(),
                result.retryAfterMs(),
                result.state().name(),
                result.items().stream().map(BatchExportResponse.ItemResponse::from).toList());
    }
}
