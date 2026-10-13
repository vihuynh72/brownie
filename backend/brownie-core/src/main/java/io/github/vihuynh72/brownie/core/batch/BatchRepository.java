package io.github.vihuynh72.brownie.core.batch;

import io.github.vihuynh72.brownie.core.export.ExportFormat;
import io.github.vihuynh72.brownie.core.export.ExportReceipt;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * How the batch tables are reached. Every method takes the acting member's
 * user id because the tables' row-level security answers only to it; the
 * workspace's one-step-at-a-time rule is enforced by {@link
 * #acquireStepLease}, which takes the workspace's advisory lock in the same
 * transaction it writes the lease in, so two concurrent acquisitions cannot
 * both win.
 */
public interface BatchRepository {

    /** A batch as it is written, before the row it becomes exists. */
    record NewBatch(
            long workspaceId,
            long templateId,
            long templateVersionId,
            long sourceArtifactId,
            String sourceSha256,
            TableFormat tableFormat,
            String tableReaderVersion,
            String sheetName,
            boolean hasHeaderRow,
            DateOrder dateOrder,
            List<FieldMapping> mapping,
            Integer titleColumn,
            String title,
            int rowCount,
            IdempotencyKey idempotencyKey,
            String requestHash,
            long createdByUserId) {

        public NewBatch {
            mapping = List.copyOf(mapping);
        }
    }

    /** A row as it is written at creation: its number as the person sees it, its state, and its compose state. */
    record NewBatchRow(int rowNumber, BatchRowState state, ComposeState composeState) {}

    /** Creates the batch and every one of its rows in one transaction, plus its BATCH_CREATED audit row. */
    Batch create(NewBatch batch, List<NewBatchRow> rows);

    Optional<Batch> find(long workspaceId, long userId, long batchId);

    /** The batch an earlier request with this key made for this member, when it made one. */
    Optional<Batch> findByIdempotencyKey(long workspaceId, long userId, IdempotencyKey key);

    List<Batch> findAll(long workspaceId, long userId);

    /** Every row of the batch, in row order. */
    List<BatchRow> findRows(long workspaceId, long userId, long batchId);

    Optional<BatchRow> findRow(long workspaceId, long userId, long batchId, int rowNumber);

    /** The batch's PENDING rows in row order -- the set a step still owes. */
    List<BatchRow> findPendingRows(long workspaceId, long userId, long batchId);

    /** Rows still PENDING, without reading them: how many a step has left. */
    int countPendingRows(long workspaceId, long userId, long batchId);

    /** CREATED-row counts for every batch in the workspace, for the list. */
    Map<Long, Integer> createdCounts(long workspaceId, long userId);

    /** The document ids a batch's rows produced, in row order. */
    List<Long> createdDocumentIds(long workspaceId, long userId, long batchId);

    /**
     * Takes the workspace's step lease for this batch, or returns null when
     * any batch in the workspace already holds a live one.
     */
    UUID acquireStepLease(long workspaceId, long userId, long batchId, BatchStepKind kind, Duration ttl);

    /** Extends a held lease; false means the token is gone and the step holding it must stop. */
    boolean refreshStepLease(long workspaceId, long userId, long batchId, UUID token, Duration ttl);

    /** Releases a lease only while the token still names it -- never another holder's. */
    void releaseStepLease(long workspaceId, long userId, long batchId, UUID token);

    /**
     * Names the document and first revision a row's fill produced, with any
     * problems the row carried -- only while the row is still PENDING.
     * False means the row moved on without this step (the batch deleted, or
     * its document purged); the step must stop and count it done.
     */
    boolean markRowCreated(
            long workspaceId, long userId, long batchId, int rowNumber,
            long documentId, long revisionId, List<BatchRowProblem> problems);

    /** As {@link #markRowCreated}, for a row whose fill failed. */
    boolean markRowFailed(long workspaceId, long userId, long batchId, int rowNumber, List<BatchRowProblem> problems);

    /**
     * Every row of the batch with the facts the detail screen and the
     * later steps need -- the document's title and current revision, that
     * revision's content, hash and per-item states, the initial
     * revision's hash, the newest manifest on the current revision, a
     * waiting proposal, open questions on the document's latest
     * generation run, and the row's outcome in the batch's latest export.
     * Read set-based: a fixed handful of statements over the whole batch,
     * never one per row.
     */
    List<BatchRowDetail> findRowDetails(long workspaceId, long userId, long batchId);

    /**
     * Writes a row's resolved compose state (and the proposal PROPOSED
     * names, or the problems FAILED explains), only while its compose is
     * still PENDING -- the same moved-on protection {@link #markRowCreated}
     * keeps. False means another step got there first; the caller stops.
     */
    boolean markRowComposeResolved(
            long workspaceId, long userId, long batchId, int rowNumber,
            ComposeState composeState, Long proposalId, List<BatchRowProblem> problems);

    /** CREATED rows whose compose is still PENDING: what a compose step has left. */
    int countComposePendingRows(long workspaceId, long userId, long batchId);

    /** CREATED rows with a present document and no manifest on its current revision: what a check step has left. */
    int countUncheckedRows(long workspaceId, long userId, long batchId);

    /** An export run as it is written, before the row it becomes exists. */
    record NewBatchExport(
            long batchId,
            ExportFormat format,
            BatchExportState state,
            IdempotencyKey idempotencyKey,
            String requestHash,
            long createdByUserId) {
    }

    /** One export item as it is written at creation: its first outcome and, for PENDING, the content hash it approved. */
    record NewBatchExportItem(int rowNumber, BatchExportItemOutcome outcome, String approvedContentHash) {
    }

    /** Creates the export and every one of its items in one transaction. */
    BatchExport createExport(long workspaceId, NewBatchExport export, List<NewBatchExportItem> items);

    Optional<BatchExport> findExport(long workspaceId, long userId, long batchId, long exportId);

    /** The export an earlier request with this key made for this member on this batch, when it made one. */
    Optional<BatchExport> findExportByIdempotencyKey(long workspaceId, long userId, long batchId, IdempotencyKey key);

    /** Every item of the export, in row order. */
    List<BatchExportItem> findExportItems(long workspaceId, long userId, long exportId);

    /** The export's PENDING items in row order -- the set an advance still owes. */
    List<BatchExportItem> findPendingExportItems(long workspaceId, long userId, long exportId);

    /** Items still PENDING, without reading them. */
    int countPendingExportItems(long workspaceId, long userId, long exportId);

    /**
     * Writes an item's final outcome, its receipt where EXPORTED, and its
     * detail, only while the item is still PENDING -- the same moved-on
     * protection as the row markers. False means another advance got
     * there first; the caller stops.
     */
    boolean updateExportItem(
            long workspaceId, long userId, long exportId, int rowNumber,
            BatchExportItemOutcome outcome, Long exportReceiptId, String detail);

    /**
     * Marks the export COMPLETE and writes its BATCH_EXPORTED audit row
     * with the per-outcome counts, in one transaction.
     */
    void completeExport(long workspaceId, long userId, long batchId, long exportId);

    /** One item's facts for the download: its outcome and detail, its document's title, and its receipt when exported. */
    record ExportDownloadRow(
            int rowNumber,
            BatchExportItemOutcome outcome,
            String detail,
            String documentTitle,
            ExportReceipt receipt) {
    }

    /** Every item of the export in row order, joined to its document's title and its receipt. */
    List<ExportDownloadRow> findExportDownloadRows(long workspaceId, long userId, long exportId);

    /** Removes the batch and its rows through the security-definer routine. */
    BatchDeleteResult delete(long workspaceId, long userId, long batchId);
}
