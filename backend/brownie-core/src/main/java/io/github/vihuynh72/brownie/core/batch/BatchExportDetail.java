package io.github.vihuynh72.brownie.core.batch;

import java.util.List;

/**
 * An export run with its per-row items, as created, read or advanced.
 */
public record BatchExportDetail(
        BatchExport export,
        List<BatchExportItem> items) {
}
