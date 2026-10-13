package io.github.vihuynh72.brownie.core.batch;

import io.github.vihuynh72.brownie.core.export.ExportFormat;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;

/**
 * What an export run is made from: the format every item ships in and the
 * caller's idempotency key. The request hash covers the format plus the
 * batch id, so the same key with a different format is a conflict.
 */
public record BatchExportCreateRequest(
        ExportFormat format,
        IdempotencyKey idempotencyKey) {
}
