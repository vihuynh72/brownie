package io.github.vihuynh72.brownie.core.batch;

import io.github.vihuynh72.brownie.core.job.IdempotencyKey;

import java.util.List;

/**
 * Everything making a batch needs: which template and exact version, which
 * uploaded file, how to read it as a table, which columns fill which fields,
 * which column names each made document, and the key that makes a retried
 * request answer with the same batch.
 */
public record BatchCreateRequest(
        long templateId,
        long templateVersionId,
        long artifactId,
        String sheet,
        Boolean hasHeaderRow,
        DateOrder dateOrder,
        List<FieldMapping> mapping,
        Integer titleColumn,
        IdempotencyKey idempotencyKey) {

    public BatchCreateRequest {
        mapping = List.copyOf(mapping);
    }
}
