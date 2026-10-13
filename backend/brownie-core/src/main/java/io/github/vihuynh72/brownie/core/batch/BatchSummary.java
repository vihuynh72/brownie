package io.github.vihuynh72.brownie.core.batch;

import java.util.List;

/** A batch as the list knows it: the record, how many rows have documents, and those documents' ids. */
public record BatchSummary(Batch batch, int createdCount, List<Long> documentIds) {

    public BatchSummary {
        documentIds = List.copyOf(documentIds);
    }
}
