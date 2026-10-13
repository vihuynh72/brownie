package io.github.vihuynh72.brownie.api.batch;

import io.github.vihuynh72.brownie.core.batch.DateOrder;
import io.github.vihuynh72.brownie.core.batch.FieldMappingMode;

import java.util.List;

/**
 * The body of a create: which template and the exact version it is on now,
 * which upload holds the table, how the table reads, which columns fill
 * which fields, and which column names each made document. A null {@code
 * hasHeaderRow} asks the reader to work the header out; a null {@code
 * dateOrder} reads numeric dates month-first.
 */
record CreateBatchRequest(
        Long templateId,
        Long templateVersionId,
        Long artifactId,
        String sheet,
        Boolean hasHeaderRow,
        DateOrder dateOrder,
        List<MappingRequest> mapping,
        Integer titleColumn) {

    record MappingRequest(String fieldId, Integer column, FieldMappingMode mode) {}
}
