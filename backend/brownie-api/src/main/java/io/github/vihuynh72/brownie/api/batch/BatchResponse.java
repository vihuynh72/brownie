package io.github.vihuynh72.brownie.api.batch;

import io.github.vihuynh72.brownie.core.batch.Batch;
import io.github.vihuynh72.brownie.core.batch.BatchService;
import io.github.vihuynh72.brownie.core.batch.FieldMapping;

import java.time.OffsetDateTime;
import java.util.List;

/** A batch whole: what it was made from, how it reads the file, and every row's detail. */
record BatchResponse(
        long id,
        String title,
        long templateId,
        long templateVersionId,
        long sourceArtifactId,
        String tableFormat,
        String sheetName,
        boolean hasHeaderRow,
        String dateOrder,
        List<MappingResponse> mapping,
        Integer titleColumn,
        int rowCount,
        OffsetDateTime createdAt,
        List<BatchRowDetailResponse> rows) {

    static BatchResponse from(Batch batch, List<BatchService.EnrichedRow> rows) {
        return new BatchResponse(
                batch.id(),
                batch.title(),
                batch.templateId(),
                batch.templateVersionId(),
                batch.sourceArtifactId(),
                batch.tableFormat().name(),
                batch.sheetName(),
                batch.hasHeaderRow(),
                batch.dateOrder().name(),
                batch.mapping().stream().map(MappingResponse::from).toList(),
                batch.titleColumn(),
                batch.rowCount(),
                batch.createdAt(),
                rows.stream().map(BatchRowDetailResponse::from).toList());
    }

    record MappingResponse(String fieldId, int column, String mode) {

        static MappingResponse from(FieldMapping mapping) {
            return new MappingResponse(mapping.fieldId(), mapping.column(), mapping.mode().name());
        }
    }
}
