package io.github.vihuynh72.brownie.core.batch;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One fill-from-a-spreadsheet run: the template, the exact version it pins,
 * the file the table came from and that file's fingerprint and reader, so
 * every later step can prove it is still reading the same table the same
 * way. {@code mapping} and {@code titleColumn} say which columns fill which
 * fields and which names each made document.
 */
public record Batch(
        long id,
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
        String idempotencyKey,
        String requestHash,
        long createdByUserId,
        OffsetDateTime createdAt) {

    public Batch {
        mapping = List.copyOf(mapping);
    }
}
