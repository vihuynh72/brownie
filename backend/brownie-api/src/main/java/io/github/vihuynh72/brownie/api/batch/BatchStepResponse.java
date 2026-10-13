package io.github.vihuynh72.brownie.api.batch;

import io.github.vihuynh72.brownie.core.batch.BatchStepResult;

import java.util.List;

/**
 * What one step request did. The caller loops while {@code remaining} is
 * above zero; {@code retryAfterMs} is set only when capacity ran out, asking
 * the caller to pause rather than mark anything failed.
 */
record BatchStepResponse(int processed, int remaining, Long retryAfterMs, List<BatchRowResponse> rows) {

    static BatchStepResponse from(BatchStepResult result) {
        return new BatchStepResponse(
                result.processed(),
                result.remaining(),
                result.retryAfterMs(),
                result.rows().stream().map(BatchRowResponse::from).toList());
    }
}
