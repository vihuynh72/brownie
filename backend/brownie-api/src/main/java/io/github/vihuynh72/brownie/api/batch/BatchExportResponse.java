package io.github.vihuynh72.brownie.api.batch;

import io.github.vihuynh72.brownie.core.batch.BatchExportDetail;
import io.github.vihuynh72.brownie.core.batch.BatchExportItem;

import java.time.OffsetDateTime;
import java.util.List;

/** An export run with every row's outcome, as created, read or advanced. */
record BatchExportResponse(
        long id,
        long batchId,
        String format,
        String state,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        List<ItemResponse> items) {

    static BatchExportResponse from(BatchExportDetail detail) {
        return new BatchExportResponse(
                detail.export().id(),
                detail.export().batchId(),
                detail.export().format().name(),
                detail.export().state().name(),
                detail.export().createdAt(),
                detail.export().completedAt(),
                detail.items().stream().map(ItemResponse::from).toList());
    }

    record ItemResponse(int rowNumber, String outcome, Long exportReceiptId, String detail) {

        static ItemResponse from(BatchExportItem item) {
            return new ItemResponse(
                    item.rowNumber(), item.outcome().name(), item.exportReceiptId(), item.detail());
        }
    }
}
