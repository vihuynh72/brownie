package io.github.vihuynh72.brownie.api.batch;

import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.batch.Batch;
import io.github.vihuynh72.brownie.core.batch.BatchComposer;
import io.github.vihuynh72.brownie.core.batch.BatchExport;
import io.github.vihuynh72.brownie.core.batch.BatchExportItem;
import io.github.vihuynh72.brownie.core.batch.BatchExportItemOutcome;
import io.github.vihuynh72.brownie.core.batch.BatchExportState;
import io.github.vihuynh72.brownie.core.batch.BatchExportStepResult;
import io.github.vihuynh72.brownie.core.batch.BatchRepository;
import io.github.vihuynh72.brownie.core.batch.BatchRow;
import io.github.vihuynh72.brownie.core.batch.BatchRowDetail;
import io.github.vihuynh72.brownie.core.batch.BatchRowState;
import io.github.vihuynh72.brownie.core.batch.BatchService;
import io.github.vihuynh72.brownie.core.batch.BatchStepKind;
import io.github.vihuynh72.brownie.core.batch.ComposeState;
import io.github.vihuynh72.brownie.core.batch.CsvTableParser;
import io.github.vihuynh72.brownie.core.batch.DateOrder;
import io.github.vihuynh72.brownie.core.batch.TableFormat;
import io.github.vihuynh72.brownie.core.batch.XlsxTableReader;
import io.github.vihuynh72.brownie.core.export.ExportFormat;
import io.github.vihuynh72.brownie.core.export.ExportService;
import io.github.vihuynh72.brownie.core.export.StaleExportApprovalException;
import io.github.vihuynh72.brownie.core.revision.DocumentRevisionConflictException;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;
import io.github.vihuynh72.brownie.core.template.TemplateKind;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStatus;
import io.github.vihuynh72.brownie.core.validation.ValidationService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an export item records when the document moved on between the
 * step's set-read and the item's own approval call. Neither exception
 * can be produced through a route without racing another writer inside
 * one request, so the repository and the export service are mocked and
 * the item is driven through {@code advanceExport} directly.
 */
class BatchServiceExportAdvanceTest {

    private static final long WORKSPACE = 1;
    private static final long USER = 2;
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private final BatchRepository batches = mock(BatchRepository.class);
    private final TemplateRepository templates = mock(TemplateRepository.class);
    private final ExportService exports = mock(ExportService.class);

    private final BatchService service = new BatchService(
            batches,
            templates,
            mock(ArtifactService.class),
            mock(RevisionService.class),
            mock(XlsxTableReader.class),
            new CsvTableParser(),
            mock(RuleRepository.class),
            mock(ValidationService.class),
            exports,
            mock(BatchComposer.class),
            500,
            false);

    @Test
    void aStaleApprovalAtItemTimeIsTheSameChangedTheHashCheckReports() {
        arrangeOnePendingItem();
        when(exports.approve(anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new StaleExportApprovalException(55, 7, 8));

        BatchExportStepResult result = service.advanceExport(WORKSPACE, USER, 10, 20);

        assertItemChanged(result);
    }

    @Test
    void aRevisionConflictAtItemTimeIsTheSameChangedTheHashCheckReports() {
        arrangeOnePendingItem();
        when(exports.approve(anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new DocumentRevisionConflictException(55, 7, 8));

        BatchExportStepResult result = service.advanceExport(WORKSPACE, USER, 10, 20);

        assertItemChanged(result);
    }

    /** One export run holding one PENDING item whose row detail still looks exactly as it did when the run was made. */
    private void arrangeOnePendingItem() {
        OffsetDateTime now = OffsetDateTime.now();
        Batch batch = new Batch(
                10, WORKSPACE, 30, 40, 50, "source-hash", TableFormat.CSV, "csv-1",
                null, true, DateOrder.MDY, List.of(), null, "Members", 1, "key", "hash", USER, now);
        BatchExport export = new BatchExport(
                20, WORKSPACE, 10, ExportFormat.PDF, BatchExportState.RUNNING,
                "export-key", "hash", USER, now, null);
        BatchRow row = new BatchRow(
                10, 2, BatchRowState.CREATED, 55L, 6L, List.of(), ComposeState.NOT_NEEDED, null, now);
        BatchRowDetail detail = new BatchRowDetail(
                row, "Member", true, 7L, null, HASH, HASH, Map.of(), 99L, List.of(),
                false, 0, null, null);
        BatchExportItem item = new BatchExportItem(20, 2, BatchExportItemOutcome.PENDING, null, null, HASH);

        when(batches.find(WORKSPACE, USER, 10L)).thenReturn(Optional.of(batch));
        when(batches.findExport(WORKSPACE, USER, 10L, 20L)).thenReturn(Optional.of(export));
        when(batches.acquireStepLease(eq(WORKSPACE), eq(USER), eq(10L), eq(BatchStepKind.EXPORT), any()))
                .thenReturn(UUID.randomUUID());
        when(batches.findRowDetails(WORKSPACE, USER, 10L)).thenReturn(List.of(detail));
        when(batches.findPendingExportItems(WORKSPACE, USER, 20L)).thenReturn(List.of(item));
        when(batches.updateExportItem(eq(WORKSPACE), eq(USER), eq(20L), eq(2), any(), any(), any()))
                .thenReturn(true);
        when(batches.refreshStepLease(eq(WORKSPACE), eq(USER), eq(10L), any(), any())).thenReturn(true);
        when(batches.countPendingExportItems(WORKSPACE, USER, 20L)).thenReturn(0);
        when(templates.findVersion(WORKSPACE, USER, 30L, 40L)).thenReturn(Optional.of(
                new TemplateVersion(
                        40, WORKSPACE, 30, 1, 50, TemplateKind.DOCX, 60L, null,
                        TemplateVersionStatus.ACTIVATED, List.of(), now, now, null, List.of())));
        when(exports.findLatestReceipt(WORKSPACE, USER, 55L)).thenReturn(Optional.empty());
    }

    private void assertItemChanged(BatchExportStepResult result) {
        assertThat(result.items()).hasSize(1);
        BatchExportItem item = result.items().getFirst();
        assertThat(item.outcome()).isEqualTo(BatchExportItemOutcome.CHANGED);
        assertThat(item.detail()).isEqualTo("CHANGED_SINCE_APPROVAL");
        assertThat(item.exportReceiptId()).isNull();
        verify(batches).updateExportItem(
                WORKSPACE, USER, 20L, 2, BatchExportItemOutcome.CHANGED, null, "CHANGED_SINCE_APPROVAL");
        verify(batches).completeExport(WORKSPACE, USER, 10L, 20L);
        assertThat(result.state()).isEqualTo(BatchExportState.COMPLETE);
    }
}
