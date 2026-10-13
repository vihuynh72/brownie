package io.github.vihuynh72.brownie.core.batch;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One spreadsheet row's place in its batch, numbered as the person sees the
 * row in their file. A CREATED row names the document and first revision the
 * fill made for it -- the database itself keeps the two set exactly when the
 * state is CREATED.
 */
public record BatchRow(
        long batchId,
        int rowNumber,
        BatchRowState state,
        Long documentId,
        Long initialRevisionId,
        List<BatchRowProblem> problems,
        ComposeState composeState,
        Long proposalId,
        OffsetDateTime updatedAt) {

    public BatchRow {
        problems = List.copyOf(problems);
    }
}
