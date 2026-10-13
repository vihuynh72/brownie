package io.github.vihuynh72.brownie.api.batch;

import io.github.vihuynh72.brownie.core.batch.BatchSummary;

import java.time.OffsetDateTime;
import java.util.List;

/** A batch as the list carries it -- enough to open it or to find the documents it made. */
record BatchSummaryResponse(
        long id,
        String title,
        long templateId,
        long templateVersionId,
        int rowCount,
        int createdCount,
        OffsetDateTime createdAt,
        List<Long> documentIds) {

    static BatchSummaryResponse from(BatchSummary summary) {
        return new BatchSummaryResponse(
                summary.batch().id(),
                summary.batch().title(),
                summary.batch().templateId(),
                summary.batch().templateVersionId(),
                summary.batch().rowCount(),
                summary.createdCount(),
                summary.batch().createdAt(),
                summary.documentIds());
    }
}
