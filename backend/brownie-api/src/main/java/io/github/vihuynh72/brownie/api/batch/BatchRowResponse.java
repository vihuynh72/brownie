package io.github.vihuynh72.brownie.api.batch;

import io.github.vihuynh72.brownie.core.batch.BatchRow;
import io.github.vihuynh72.brownie.core.batch.BatchRowProblem;

import java.time.OffsetDateTime;
import java.util.List;

/** One row of a batch as the detail and step responses carry it. */
record BatchRowResponse(
        int rowNumber,
        String state,
        Long documentId,
        Long initialRevisionId,
        List<ProblemResponse> problems,
        String composeState,
        Long proposalId,
        OffsetDateTime updatedAt) {

    static BatchRowResponse from(BatchRow row) {
        return new BatchRowResponse(
                row.rowNumber(),
                row.state().name(),
                row.documentId(),
                row.initialRevisionId(),
                row.problems().stream().map(ProblemResponse::from).toList(),
                row.composeState().name(),
                row.proposalId(),
                row.updatedAt());
    }

    record ProblemResponse(String code, String fieldId, Integer column) {

        static ProblemResponse from(BatchRowProblem problem) {
            return new ProblemResponse(problem.code().name(), problem.fieldId(), problem.column());
        }
    }
}
