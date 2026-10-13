package io.github.vihuynh72.brownie.core.batch;

/**
 * One batch row inside one export run. {@code approvedContentHash} is the
 * SHA-256 of the row's document content the moment the run was made --
 * written for PENDING items only, and compared against the document's
 * live hash at advance time so a document edited since is reported
 * CHANGED rather than exported as if it were still the approved bytes.
 * {@code detail} is a code (and a field id where one applies), never
 * anything a person wrote.
 */
public record BatchExportItem(
        long batchExportId,
        int rowNumber,
        BatchExportItemOutcome outcome,
        Long exportReceiptId,
        String detail,
        String approvedContentHash) {
}
