package io.github.vihuynh72.brownie.api.batch;

import io.github.vihuynh72.brownie.core.batch.AcceptRowResult;
import io.github.vihuynh72.brownie.core.batch.BatchAcceptResult;

import java.util.List;

/**
 * What one accept step did: how many rows got their review revision, how
 * many remain accept-eligible, and every row's verdict -- ACCEPTED, or
 * SKIPPED with the code that says why.
 */
record BatchAcceptResponse(int processed, int remaining, Long retryAfterMs, List<AcceptRowResponse> rows) {

    static BatchAcceptResponse from(BatchAcceptResult result) {
        return new BatchAcceptResponse(
                result.processed(),
                result.remaining(),
                result.retryAfterMs(),
                result.rows().stream().map(AcceptRowResponse::from).toList());
    }

    record AcceptRowResponse(int rowNumber, String state, Long documentId, String result, String code) {

        static AcceptRowResponse from(AcceptRowResult row) {
            return new AcceptRowResponse(
                    row.row().rowNumber(),
                    row.row().state().name(),
                    row.row().documentId(),
                    row.accepted() ? "ACCEPTED" : "SKIPPED",
                    row.code() == null ? null : row.code().name());
        }
    }
}
